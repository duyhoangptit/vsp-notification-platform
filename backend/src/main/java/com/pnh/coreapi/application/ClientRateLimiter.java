package com.pnh.coreapi.application;

import com.pnh.coreapi.ClientPrincipal;
import com.pnh.shared.infra.RedisGuard;
import com.pnh.shared.web.PnhException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Per-client limit (research R11): Redisson {@code RRateLimiter rl:{clientId}} (exact across replicas); during a
 * Redis outage a local Bucket4j bucket of {@code rate / replica-count} (fail-open but bounded).
 */
@Service
public class ClientRateLimiter {

    private final RedissonClient redisson;
    private final RedisGuard redis;
    private final int replicaCount;
    private final Map<String, Integer> configuredRates = new ConcurrentHashMap<>();
    private final Map<String, LocalBucket> localBuckets = new ConcurrentHashMap<>();

    private record LocalBucket(int rate, Bucket bucket) {}

    public ClientRateLimiter(
            RedissonClient redisson, RedisGuard redis, @Value("${pnh.ratelimit.replica-count:1}") int replicaCount) {
        this.redisson = redisson;
        this.redis = redis;
        this.replicaCount = Math.max(1, replicaCount);
    }

    public void acquire(ClientPrincipal client) {
        int rate = client.rateLimitPerMin();
        boolean allowed = redis.call(
                () -> {
                    RRateLimiter limiter = redisson.getRateLimiter("rl:" + client.clientId());
                    Integer known = configuredRates.get(client.clientId());
                    if (known == null) {
                        if (!limiter.trySetRate(RateType.OVERALL, rate, Duration.ofMinutes(1))) {
                            limiter.setRate(RateType.OVERALL, rate, Duration.ofMinutes(1));
                        }
                        configuredRates.put(client.clientId(), rate);
                    } else if (known != rate) {
                        limiter.setRate(RateType.OVERALL, rate, Duration.ofMinutes(1));
                        configuredRates.put(client.clientId(), rate);
                    }
                    return limiter.tryAcquire();
                },
                () -> local(client.clientId(), rate).tryConsume(1));
        if (!allowed) {
            throw PnhException.rateLimited(Math.max(1, (long) Math.ceil(60.0 / rate)));
        }
    }

    private Bucket local(String clientId, int rate) {
        LocalBucket lb = localBuckets.compute(clientId, (id, existing) -> {
            if (existing != null && existing.rate() == rate) {
                return existing;
            }
            long capacity = Math.max(1, rate / replicaCount);
            return new LocalBucket(
                    rate,
                    Bucket.builder()
                            .addLimit(Bandwidth.builder()
                                    .capacity(capacity)
                                    .refillGreedy(capacity, Duration.ofMinutes(1))
                                    .build())
                            .build());
        });
        return lb.bucket();
    }
}
