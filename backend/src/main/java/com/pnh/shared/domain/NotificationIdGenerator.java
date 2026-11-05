package com.pnh.shared.domain;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562, method 1: 12-bit monotonic counter in rand_a) whose 48-bit timestamp comes from the injected
 * {@link Clock} — the same source as {@code created_at} (research R29, fix U4).
 */
public final class NotificationIdGenerator {

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private long lastMillis = -1;
    private long counter;

    public NotificationIdGenerator(Clock clock) {
        this.clock = clock;
    }

    /** Backward steps larger than this are clock switches (override reset/move), not jitter: follow the clock. */
    static final long MAX_MONOTONIC_REWIND_MS = 1_000;

    public synchronized UUID next() {
        long millis = clock.millis();
        if (millis < lastMillis - MAX_MONOTONIC_REWIND_MS) {
            lastMillis =
                    -1; // the clock was moved back (R29): ids follow the overridden time, uniqueness from random bits
        }
        if (millis <= lastMillis) {
            millis = lastMillis;
            counter++;
            if (counter > 0xFFF) { // counter overflow → advance the logical timestamp
                millis = ++lastMillis;
                counter = random.nextInt(0x200);
            }
        } else {
            counter = random.nextInt(0x200); // leave headroom for increments
        }
        lastMillis = millis;
        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | (counter & 0xFFFL);
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    public static long timestampMillis(UUID id) {
        return id.getMostSignificantBits() >>> 16;
    }
}
