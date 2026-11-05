package com.pnh.alerting.application;

import com.pnh.alerting.domain.AlertRuleCode;
import com.pnh.coreapi.IngestRejected;
import com.pnh.provider.ProviderAvailabilityChanged;
import com.pnh.shared.infra.RedisGuard;
import com.pnh.tracking.NotificationOutcome;
import com.pnh.tracking.NotificationStuckFailed;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sliding-window alert signals (T151, R24): Redisson scored sets {@code alert:win:{rule}:{channel}} (score = Clock millis),
 * mirrored in a bounded local window used when Redis is unavailable (the evaluator falls back to Postgres counts for
 * stuck/failure-rate signals). Provider circuit state is tracked per provider key.
 */
@Component
public class AlertSignalCollector {

    private static final Logger log = LoggerFactory.getLogger(AlertSignalCollector.class);
    static final Duration RETENTION = Duration.ofHours(25);
    private static final int LOCAL_MAX = 100_000;

    /** An open provider circuit. */
    public record OpenCircuit(String channelType, String providerKey, Instant since) {}

    private final RedissonClient redisson;
    private final RedisGuard redis;
    private final Clock clock;
    private final Map<String, Deque<long[]>> local = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> localMembers = new ConcurrentHashMap<>();
    private final Map<String, OpenCircuit> openCircuits = new ConcurrentHashMap<>();

    public AlertSignalCollector(RedissonClient redisson, RedisGuard redis, Clock clock) {
        this.redisson = redisson;
        this.redis = redis;
        this.clock = clock;
    }

    public static String key(AlertRuleCode rule, String channel, String suffix) {
        return "alert:win:" + rule.name() + ":" + (channel == null ? "-" : channel)
                + (suffix == null ? "" : ":" + suffix);
    }

    @EventListener
    public void on(NotificationStuckFailed e) {
        afterCommit(() -> record(
                key(AlertRuleCode.STUCK_DELIVERY_SPIKE, e.channelType(), null),
                e.notificationId().toString()));
    }

    @EventListener
    public void on(NotificationOutcome e) {
        afterCommit(() -> onOutcome(e));
    }

    private void onOutcome(NotificationOutcome e) {
        if ("SENT".equals(e.status()) || "DELIVERED".equals(e.status()) && e.sequence() == 1) {
            record(
                    key(AlertRuleCode.CHANNEL_FAILURE_RATE, e.channelType(), "ok"),
                    e.notificationId().toString());
        } else if ("FAILED".equals(e.status())) {
            record(
                    key(AlertRuleCode.CHANNEL_FAILURE_RATE, e.channelType(), "fail"),
                    e.notificationId().toString());
        }
    }

    @EventListener
    public void on(IngestRejected e) {
        afterCommit(() -> record(
                key(AlertRuleCode.INGEST_REJECTED_SPIKE, null, null),
                UUID.randomUUID().toString()));
    }

    /**
     * Signals are window counters, not durable work: they are recorded after the publisher's commit (or immediately
     * without a transaction) WITHOUT a Modulith event publication. A registry-tracked after-commit listener opens a
     * second transaction while the publisher's connection is still bound, which exhausts the pool under load.
     */
    private static void afterCommit(Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                log.warn("Alert signal not recorded: {}", ex.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }

    @EventListener
    public void on(ProviderAvailabilityChanged e) {
        if ("OPEN".equals(e.state()) || "FORCED_OPEN".equals(e.state())) {
            openCircuits.putIfAbsent(e.providerKey(), new OpenCircuit(e.channelType(), e.providerKey(), e.since()));
        } else if ("CLOSED".equals(e.state()) || "DISABLED".equals(e.state())) {
            openCircuits.remove(e.providerKey());
        }
        // HALF_OPEN keeps the original "since": the provider is not healthy yet
    }

    public Collection<OpenCircuit> openCircuits() {
        return List.copyOf(openCircuits.values());
    }

    void record(String key, String member) {
        long now = clock.millis();
        local.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>()).addLast(new long[] {now});
        localMembers.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>()).addLast(member);
        trimLocal(key);
        redis.run(() -> {
            RScoredSortedSet<String> set = redisson.getScoredSortedSet(key, StringCodec.INSTANCE);
            set.add(now, member + "|" + now);
            set.expire(RETENTION);
        });
    }

    /** Count in the window from Redis; empty when Redis is unavailable (caller falls back). */
    public Optional<Long> countRedis(String key, Instant since) {
        return redis.call(
                () -> {
                    RScoredSortedSet<String> set = redisson.getScoredSortedSet(key, StringCodec.INSTANCE);
                    set.removeRangeByScore(
                            0, true, clock.instant().minus(RETENTION).toEpochMilli(), true);
                    return Optional.of((long) set.count(since.toEpochMilli(), true, clock.millis(), true));
                },
                Optional::empty);
    }

    public long countLocal(String key, Instant since) {
        Deque<long[]> d = local.get(key);
        if (d == null) {
            return 0;
        }
        long from = since.toEpochMilli();
        long to =
                clock.millis(); // windows end at the Clock's now: signals stamped later (clock moved back) do not count
        return d.stream().filter(t -> t[0] >= from && t[0] <= to).count();
    }

    /** Up to {@code max} sample members (notification ids) in the window. */
    public List<String> samples(String key, Instant since, int max) {
        List<String> fromRedis = redis.call(
                () -> {
                    List<String> out = new ArrayList<>();
                    for (String m : redisson.<String>getScoredSortedSet(key, StringCodec.INSTANCE)
                            .valueRangeReversed(since.toEpochMilli(), true, clock.millis(), true, 0, max)) {
                        out.add(m.substring(0, m.indexOf('|')));
                    }
                    return out;
                },
                List::of);
        if (!fromRedis.isEmpty()) {
            return fromRedis;
        }
        Deque<String> members = localMembers.get(key);
        return members == null
                ? List.of()
                : members.stream().skip(Math.max(0, members.size() - max)).toList();
    }

    private void trimLocal(String key) {
        Deque<long[]> d = local.get(key);
        Deque<String> m = localMembers.get(key);
        long floor = clock.instant().minus(RETENTION).toEpochMilli();
        while (d.size() > LOCAL_MAX || !d.isEmpty() && d.peekFirst()[0] < floor) {
            d.pollFirst();
            if (m != null) {
                m.pollFirst();
            }
        }
    }
}
