package com.vsp.notification.shared.time;

import com.vsp.notification.shared.infra.PartitionService;
import com.vsp.notification.shared.infra.RedisAvailability;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Follows the VSP system clock owned by <b>vsp-catalog-service</b> ({@code PUT/DELETE /api/v1/admin/system-clock})
 * in non-production environments. Catalog keeps the state in its database and publishes it on Redis key
 * {@code vsp:clock:override} (absent = SYSTEM) and topic {@code vsp:clock}; this service only applies it.
 *
 * <p>Partitions for the target month (+3) are created BEFORE switching; if that fails the change is ignored (the
 * current time stays in effect) and logged. Redis down → configured mode stays in effect; a periodic resync re-reads
 * the key so a missed broadcast is caught up.
 */
@Service
public class ClockOverrideService {

    private static final Logger log = LoggerFactory.getLogger(ClockOverrideService.class);
    private static final int PARTITION_ATTEMPTS = 3;

    /** Contract published by vsp-catalog-service ({@code ClockOverrideMessage}); JSON snake_case. */
    record StoredState(String mode, Instant fixedInstant, String offset, String changedBy, Instant changedAt) {}

    private final PnhClock clock;
    private final ClockProperties properties;
    private final RedissonClient redisson;
    private final RedisAvailability availability;
    private final PartitionService partitions;
    private final JsonMapper json;
    private volatile StoredState applied;
    private volatile Integer listenerId;

    public ClockOverrideService(
            PnhClock clock,
            ClockProperties properties,
            RedissonClient redisson,
            RedisAvailability availability,
            PartitionService partitions,
            JsonMapper json,
            MeterRegistry meters) {
        this.clock = clock;
        this.properties = properties;
        this.redisson = redisson;
        this.availability = availability;
        this.partitions = partitions;
        this.json = json;
        Gauge.builder("pnh.clock.override.active", clock, c -> c.mode() == ClockMode.SYSTEM ? 0 : 1)
                .register(meters);
    }

    public boolean overrideEnabled() {
        return properties.overrideEnabled();
    }

    public ClockState state() {
        Duration offset = clock.mode() == ClockMode.OFFSET ? clock.offset() : null;
        StoredState s = applied;
        return new ClockState(
                clock.mode(),
                clock.instant(),
                clock.realInstant(),
                clock.fixedInstant(),
                offset == null ? null : offset.toString(),
                properties.overrideEnabled(),
                s == null ? null : s.changedBy(),
                s == null ? null : s.changedAt());
    }

    /** Applies a published state; a non-SYSTEM state whose partitions cannot be created is ignored. */
    synchronized void apply(StoredState s) {
        if (Objects.equals(s, applied)) {
            return;
        }
        ClockMode mode = ClockMode.valueOf(s.mode());
        if (mode != ClockMode.SYSTEM && !ensurePartitions(s)) {
            return;
        }
        switch (mode) {
            case SYSTEM -> clock.apply(properties);
            case FIXED -> clock.setFixed(Objects.requireNonNull(s.fixedInstant(), "fixed_instant"));
            case OFFSET -> clock.setOffset(Duration.parse(s.offset()));
        }
        applied = s;
        if (mode != ClockMode.SYSTEM) {
            log.warn("System time override active (set by {}): {} -> now = {}", s.changedBy(), mode, clock.instant());
        } else {
            log.info("System time back to configured mode {}", properties.mode());
        }
    }

    /**
     * Every instance receives the broadcast at the same moment and creates the partitions concurrently; the loser of
     * that race gets "relation already exists", so retry (the partitions then exist and the call is a no-op).
     */
    private boolean ensurePartitions(StoredState s) {
        for (int attempt = 1; ; attempt++) {
            try {
                partitions.ensureMonths(targetOf(s), 3);
                return true;
            } catch (RuntimeException e) {
                if (attempt >= PARTITION_ATTEMPTS) {
                    log.warn("Ignoring clock override {}: partitions for the target time could not be created", s, e);
                    return false;
                }
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(100, 300) * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    private RTopic topic() {
        return redisson.getTopic(properties.topic(), StringCodec.INSTANCE);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadAndSubscribe() {
        if (!properties.overrideEnabled()) {
            return;
        }
        subscribe();
        resync();
    }

    private void subscribe() {
        if (listenerId != null) {
            return;
        }
        try {
            listenerId = topic().addListener(String.class, (channel, msg) -> {
                try {
                    apply(json.readValue(msg, StoredState.class));
                } catch (RuntimeException e) {
                    log.warn("Ignoring invalid clock override message", e);
                }
            });
            availability.markSuccess();
        } catch (RuntimeException e) {
            availability.markFailure(e);
            log.warn(
                    "Could not subscribe to {} (Redis unavailable); using configured mode {}",
                    properties.topic(),
                    properties.mode());
        }
    }

    /** Re-reads the key: catches up after a missed broadcast or a Redis outage. */
    @Scheduled(
            fixedDelayString = "${pnh.clock.resync-interval:60s}",
            initialDelayString = "${pnh.clock.resync-interval:60s}")
    public void scheduledResync() {
        if (properties.overrideEnabled()) {
            subscribe();
            resync();
        }
    }

    void resync() {
        try {
            Object stored = redisson.getBucket(properties.redisKey(), StringCodec.INSTANCE)
                    .get();
            availability.markSuccess();
            if (stored != null) {
                apply(json.readValue(stored.toString(), StoredState.class));
            } else if (applied != null && ClockMode.valueOf(applied.mode()) != ClockMode.SYSTEM) {
                apply(new StoredState(ClockMode.SYSTEM.name(), null, null, "resync", clock.realInstant()));
            }
        } catch (RuntimeException e) {
            availability.markFailure(e);
            log.warn("Clock override state not loaded (Redis unavailable); keeping mode {}", clock.mode());
        }
    }

    private Instant targetOf(StoredState s) {
        return switch (ClockMode.valueOf(s.mode())) {
            case FIXED -> s.fixedInstant();
            case OFFSET -> clock.realInstant().plus(Duration.parse(s.offset()));
            case SYSTEM -> clock.realInstant();
        };
    }
}
