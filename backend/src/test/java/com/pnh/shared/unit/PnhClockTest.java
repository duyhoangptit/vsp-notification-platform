package com.pnh.shared.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pnh.shared.time.ClockMode;
import com.pnh.shared.time.ClockProperties;
import com.pnh.shared.time.PnhClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PnhClockTest {

    private static final Instant BASE = Instant.parse("2026-11-03T10:00:00Z");
    private final PnhClock clock = new PnhClock(Clock.fixed(BASE, ZoneOffset.UTC));

    @Test
    void systemModeDelegatesToBase() {
        assertThat(clock.mode()).isEqualTo(ClockMode.SYSTEM);
        assertThat(clock.instant()).isEqualTo(BASE);
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock.realInstant()).isEqualTo(BASE);
    }

    @Test
    void fixedModeReturnsConfiguredInstant() {
        Instant fixed = Instant.parse("2027-01-01T08:00:00Z");
        clock.setFixed(fixed);
        assertThat(clock.mode()).isEqualTo(ClockMode.FIXED);
        assertThat(clock.instant()).isEqualTo(fixed);
        assertThat(clock.millis()).isEqualTo(fixed.toEpochMilli());
        assertThat(clock.realInstant()).isEqualTo(BASE);
    }

    @Test
    void offsetModeAddsDurationIncludingNegative() {
        clock.setOffset(Duration.ofDays(30));
        assertThat(clock.instant()).isEqualTo(BASE.plus(Duration.ofDays(30)));
        clock.setOffset(Duration.ofHours(-2));
        assertThat(clock.instant()).isEqualTo(BASE.minus(Duration.ofHours(2)));
        assertThat(clock.offset()).isEqualTo(Duration.ofHours(-2));
    }

    @Test
    void resetReturnsToSystem() {
        clock.setFixed(Instant.EPOCH);
        clock.reset();
        assertThat(clock.mode()).isEqualTo(ClockMode.SYSTEM);
        assertThat(clock.instant()).isEqualTo(BASE);
    }

    @Test
    void withZoneKeepsTheOverride() {
        Instant fixed = Instant.parse("2027-01-01T08:00:00Z");
        Clock zoned = clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
        clock.setFixed(fixed);
        assertThat(zoned.instant()).isEqualTo(fixed);
        assertThat(zoned.getZone()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    @Test
    void switchingIsThreadSafe() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(8);
        for (int t = 0; t < 8; t++) {
            int id = t;
            pool.submit(() -> {
                for (int i = 0; i < 2000; i++) {
                    if (id % 2 == 0) {
                        clock.setOffset(Duration.ofSeconds(i));
                    } else {
                        Instant now = clock.instant(); // must never throw / observe a torn state
                        assertThat(now).isNotNull();
                    }
                }
                done.countDown();
            });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(clock.mode()).isEqualTo(ClockMode.OFFSET);
    }

    @Test
    void propertiesValidation() {
        assertThatThrownBy(() -> new ClockProperties(ClockMode.FIXED, null, null, true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixed-instant");
        assertThatThrownBy(() -> new ClockProperties(ClockMode.OFFSET, null, null, true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("offset");
        assertThatThrownBy(() -> new ClockProperties(ClockMode.OFFSET, null, "30 days", true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ISO-8601");
        new ClockProperties(ClockMode.OFFSET, null, "-PT2H", true).validate();
        new ClockProperties(ClockMode.FIXED, Instant.EPOCH, null, true).validate();
        new ClockProperties(null, null, null, false).validate();
    }

    @Test
    void applyPropertiesSetsMode() {
        clock.apply(new ClockProperties(ClockMode.OFFSET, null, "P1D", false));
        assertThat(clock.instant()).isEqualTo(BASE.plus(Duration.ofDays(1)));
    }
}
