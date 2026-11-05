package com.pnh.shared.infra;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Runs an optional Redis operation with a fallback. After a failure Redis is skipped for a short back-off so request
 * latency does not pay a timeout on every call during an outage (constitution: Redis is never required).
 */
@Component
public class RedisGuard {

    private static final long BACKOFF_NANOS = 5_000_000_000L;
    private final RedisAvailability availability;
    private volatile long skipUntil;

    public RedisGuard(RedisAvailability availability) {
        this.availability = availability;
    }

    public <T> T call(Supplier<T> redisOp, Supplier<T> fallback) {
        if (System.nanoTime() - skipUntil < 0) {
            return fallback.get();
        }
        try {
            T result = redisOp.get();
            availability.markSuccess();
            return result;
        } catch (RuntimeException e) {
            availability.markFailure(e);
            skipUntil = System.nanoTime() + BACKOFF_NANOS;
            return fallback.get();
        }
    }

    public void run(Runnable redisOp) {
        call(
                () -> {
                    redisOp.run();
                    return null;
                },
                () -> null);
    }
}
