package com.pnh.shared.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.shared.domain.NotificationIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationIdTest {

    @Test
    void uniqueAndTimeOrderedUnderFixedClock() {
        Clock fixed = Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC);
        NotificationIdGenerator gen = new NotificationIdGenerator(fixed);
        Set<UUID> seen = new HashSet<>();
        List<UUID> ordered = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            UUID id = gen.next();
            assertThat(seen.add(id)).isTrue();
            ordered.add(id);
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
        }
        assertThat(NotificationIdGenerator.timestampMillis(ordered.get(0))).isEqualTo(fixed.millis());
        assertThat(NotificationIdGenerator.timestampMillis(ordered.get(99_999))).isGreaterThanOrEqualTo(fixed.millis());
        // monotonic within the same millisecond (string order == byte order for UUIDv7)
        for (int i = 1; i < ordered.size(); i++) {
            assertThat(ordered.get(i).toString())
                    .isGreaterThan(ordered.get(i - 1).toString());
        }
    }

    @Test
    void followsTheClockAfterItIsMovedBack() {
        com.pnh.shared.time.PnhClock clock =
                new com.pnh.shared.time.PnhClock(Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC));
        NotificationIdGenerator gen = new NotificationIdGenerator(clock);
        clock.setFixed(Instant.parse("2035-06-01T10:00:00Z"));
        UUID future = gen.next();
        clock.reset();
        UUID now = gen.next();
        assertThat(NotificationIdGenerator.timestampMillis(future))
                .isEqualTo(Instant.parse("2035-06-01T10:00:00Z").toEpochMilli());
        assertThat(NotificationIdGenerator.timestampMillis(now))
                .isEqualTo(Instant.parse("2026-11-03T10:00:00Z").toEpochMilli());
        assertThat(now).isNotEqualTo(future);
    }
}
