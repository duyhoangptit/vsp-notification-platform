package com.pnh.tracking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.shared.infra.DistributedLockService;
import com.pnh.shared.infra.PartitionService;
import com.pnh.support.IntegrationTestBase;
import com.pnh.tracking.adapter.out.RetentionJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** T176 — 12-month retention with clock-override safety (R9, R29 fix H3). */
class RetentionJobIT extends IntegrationTestBase {

    @Autowired
    RetentionJob retention;

    @Autowired
    PartitionService partitions;

    @Autowired
    DistributedLockService locks;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    MeterRegistry meters;

    private boolean exists(String name) {
        return jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name);
    }

    private void oldKey(String requestId, Instant at) {
        jdbc.update(
                "INSERT INTO pnh_request_keys (client_id, client_request_id, notification_id, created_at) VALUES (?,?,?,?)",
                "ret-client",
                requestId,
                UUID.randomUUID(),
                Timestamp.from(at));
    }

    @Test
    void dropsOldPartitionsAndKeysKeepsRecent() {
        partitions.ensureMonths(Instant.parse("2010-01-15T00:00:00Z"), 0);
        String oldKey = "old-" + UUID.randomUUID();
        String newKey = "new-" + UUID.randomUUID();
        oldKey(oldKey, Instant.parse("2010-01-15T00:00:00Z"));
        oldKey(newKey, clock.instant().minus(Duration.ofDays(30)));
        String current =
                "pnh_notifications_history_y" + clock.instant().toString().substring(0, 4) + "m"
                        + clock.instant().toString().substring(5, 7);

        RetentionJob.Result r = retention.runNow();
        assertThat(r.ran()).isTrue();
        assertThat(r.droppedPartitions())
                .contains("pnh_notifications_history_y2010m01", "pnh_notification_events_y2010m01");
        assertThat(exists("pnh_notifications_history_y2010m01")).isFalse();
        assertThat(exists(current)).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pnh_request_keys WHERE client_request_id IN (?, ?)",
                        Integer.class,
                        oldKey,
                        newKey))
                .isEqualTo(1);
    }

    @Test
    void skippedWhileClockOverriddenUnlessExplicitlyEnabled() {
        partitions.ensureMonths(Instant.parse("2009-03-15T00:00:00Z"), 0);
        double before = meters.counter("pnh.retention.skipped", "reason", "clock_override")
                .count();
        clock.setOffset(Duration.ofDays(730));
        RetentionJob.Result skipped = retention.runNow();
        assertThat(skipped.ran()).isFalse();
        assertThat(exists("pnh_notifications_history_y2009m03")).isTrue();
        assertThat(meters.counter("pnh.retention.skipped", "reason", "clock_override")
                        .count())
                .isEqualTo(before + 1);

        // flag false → follows the overridden clock (FIXED in 2010-06 → cutoff 2009-06, so only 2009-03 goes)
        clock.setFixed(Instant.parse("2010-06-10T00:00:00Z"));
        RetentionJob followsClock = new RetentionJob(jdbcClient, locks, clock, meters, 12, false, false);
        RetentionJob.Result r = followsClock.runNow();
        assertThat(r.ran()).isTrue();
        assertThat(exists("pnh_notifications_history_y2009m03")).isFalse();
    }

    @Test
    void lockGuardedSingleRun() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread other = new Thread(() -> locks.tryRunExclusive("retention", () -> {
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        other.start();
        held.await(10, TimeUnit.SECONDS);
        try {
            assertThat(retention.runNow().ran()).isFalse();
        } finally {
            release.countDown();
            other.join(10_000);
        }
    }
}
