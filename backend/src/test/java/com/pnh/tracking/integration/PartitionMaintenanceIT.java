package com.pnh.tracking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.shared.domain.NotificationIdGenerator;
import com.pnh.shared.infra.DistributedLockService;
import com.pnh.shared.infra.PartitionService;
import com.pnh.support.IntegrationTestBase;
import com.pnh.support.NotificationFixtures;
import com.pnh.tracking.TrackingApi;
import com.pnh.tracking.adapter.out.PartitionMaintenanceJob;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** T024 */
class PartitionMaintenanceIT extends IntegrationTestBase {

    @Autowired
    PartitionService partitions;

    @Autowired
    PartitionMaintenanceJob job;

    @Autowired
    DistributedLockService locks;

    @Autowired
    TrackingApi tracking;

    @Autowired
    NotificationIdGenerator ids;

    private boolean exists(String table, ZonedDateTime month) {
        String name = String.format("%s_y%04dm%02d", table, month.getYear(), month.getMonthValue());
        return jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name);
    }

    @Test
    void createsMissingPartitionsIdempotently() {
        Instant base = Instant.parse("2031-05-17T10:00:00Z");
        partitions.ensureMonths(base, 3);
        assertThat(partitions.ensureMonths(base, 3)).isZero(); // idempotent
        ZonedDateTime m = base.atZone(ZoneOffset.UTC);
        for (int i = 0; i <= 3; i++) {
            assertThat(exists("pnh_notifications_history", m.plusMonths(i))).isTrue();
            assertThat(exists("pnh_notification_events", m.plusMonths(i))).isTrue();
        }
    }

    @Test
    void jobKeepsThreeMonthsAheadAndInsertInMonthPlusTwoSucceeds() {
        clock.setFixed(Instant.parse("2032-02-10T00:00:00Z"));
        assertThat(job.run()).isTrue();
        clock.setFixed(Instant.parse("2032-04-20T00:00:00Z")); // month + 2
        var created = tracking.createReceived(NotificationFixtures.email(ids));
        assertThat(created.createdAt()).isEqualTo(Instant.parse("2032-04-20T00:00:00Z"));
    }

    @Test
    void jobSkipsWhenLockHeldElsewhere() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread other = new Thread(() -> locks.tryRunExclusive("partition-maintenance", () -> {
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
            assertThat(job.run()).isFalse();
        } finally {
            release.countDown();
            other.join(10_000);
        }
    }
}
