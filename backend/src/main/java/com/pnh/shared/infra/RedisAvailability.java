package com.pnh.shared.infra;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Tracks Redis failures observed by callers. Exposes gauge {@code pnh_redis_fallback_active} (1 while the last Redis
 * operation failed) and a health indicator that reports DEGRADED-but-UP (Redis is optional).
 */
@Component("redisAvailability")
public class RedisAvailability implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(RedisAvailability.class);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    public RedisAvailability(MeterRegistry registry) {
        Gauge.builder("pnh.redis.fallback.active", this, r -> r.isDegraded() ? 1 : 0)
                .description("1 while Redis is unavailable and fallbacks are in use")
                .register(registry);
    }

    public void markSuccess() {
        if (consecutiveFailures.getAndSet(0) > 0) {
            log.info("Redis reachable again; leaving fallback mode");
        }
    }

    public int markFailure(Throwable cause) {
        int n = consecutiveFailures.incrementAndGet();
        if (n == 1) {
            log.warn("Redis operation failed, using fallback: {}", cause.toString());
        }
        return n;
    }

    public boolean isDegraded() {
        return consecutiveFailures.get() > 0;
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** Redis is optional: when it is down the service still works on fallbacks, so it reports DEGRADED (HTTP 200). */
    public static final org.springframework.boot.health.contributor.Status DEGRADED =
            new org.springframework.boot.health.contributor.Status("DEGRADED", "Redis unavailable - fallbacks active");

    @Override
    public Health health() {
        return isDegraded()
                ? Health.status(DEGRADED)
                        .withDetail("redis", "DOWN")
                        .withDetail("failures", consecutiveFailures.get())
                        .build()
                : Health.up().withDetail("redis", "UP").build();
    }
}
