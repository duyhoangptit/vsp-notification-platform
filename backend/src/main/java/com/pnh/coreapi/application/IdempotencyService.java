package com.pnh.coreapi.application;

import com.pnh.coreapi.adapter.out.RequestKeyRepository;
import com.pnh.shared.infra.RedisGuard;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Idempotency on (client_id, client_request_id) (T088): Redis {@code idem:*} fast path (5 min) in front of the
 * authoritative Postgres key table. Redis is written only after commit, so it never points to a rolled-back id.
 */
@Service
public class IdempotencyService {

    private static final Duration FAST_PATH_TTL = Duration.ofMinutes(5);

    private final RequestKeyRepository keys;
    private final RedissonClient redisson;
    private final RedisGuard redis;
    private final Clock clock;

    public IdempotencyService(RequestKeyRepository keys, RedissonClient redisson, RedisGuard redis, Clock clock) {
        this.keys = keys;
        this.redisson = redisson;
        this.redis = redis;
        this.clock = clock;
    }

    static String redisKey(String clientId, String clientRequestId) {
        return "idem:" + clientId + ":" + clientRequestId;
    }

    public Optional<UUID> existing(String clientId, String clientRequestId) {
        Optional<UUID> cached = redis.call(
                () -> Optional.ofNullable(
                                redisson.<String>getBucket(redisKey(clientId, clientRequestId), StringCodec.INSTANCE)
                                        .get())
                        .map(UUID::fromString),
                Optional::empty);
        return cached.isPresent() ? cached : keys.find(clientId, clientRequestId);
    }

    /** Must run inside the acceptance transaction. */
    public boolean claim(String clientId, String clientRequestId, UUID notificationId) {
        boolean inserted = keys.insertIfAbsent(clientId, clientRequestId, notificationId, clock.instant());
        if (inserted && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redis.run(
                            () -> redisson.<String>getBucket(redisKey(clientId, clientRequestId), StringCodec.INSTANCE)
                                    .setIfAbsent(notificationId.toString(), FAST_PATH_TTL));
                }
            });
        }
        return inserted;
    }

    public Optional<UUID> authoritative(String clientId, String clientRequestId) {
        return keys.find(clientId, clientRequestId);
    }
}
