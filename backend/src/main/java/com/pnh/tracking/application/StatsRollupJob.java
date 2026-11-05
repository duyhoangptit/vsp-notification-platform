package com.pnh.tracking.application;

import com.pnh.shared.infra.DistributedLockService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hourly rollups (T163, R10): every minute, recompute the current and previous hour from history (latest status per
 * notification, bucketed by creation hour) with INSERT … ON CONFLICT DO UPDATE — exact and idempotent.
 */
@Service
public class StatsRollupJob {

    private final JdbcClient jdbc;
    private final DistributedLockService locks;
    private final Clock clock;
    private final boolean jobsEnabled;

    public StatsRollupJob(
            JdbcClient jdbc,
            DistributedLockService locks,
            Clock clock,
            @Value("${pnh.jobs.enabled:true}") boolean jobsEnabled) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.clock = clock;
        this.jobsEnabled = jobsEnabled;
    }

    @Scheduled(fixedDelayString = "${pnh.stats.interval:60s}", initialDelayString = "${pnh.stats.initial-delay:30s}")
    void scheduled() {
        if (jobsEnabled) {
            locks.tryRunExclusive("stats-rollup", () -> {
                Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS);
                recompute(hour.minus(Duration.ofHours(1)), hour.plus(Duration.ofHours(1)));
            });
        }
    }

    /** Recomputes every hour bucket in [from, to) (both truncated to the hour). */
    @Transactional
    public void recompute(Instant from, Instant to) {
        Instant start = from.truncatedTo(ChronoUnit.HOURS);
        Instant end = to.truncatedTo(ChronoUnit.HOURS);
        if (end.isBefore(to)) {
            end = end.plus(Duration.ofHours(1));
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql(
                        "UPDATE pnh_notification_stats_hourly SET count = 0, recomputed_at = :now WHERE bucket_hour >= :from AND bucket_hour < :to")
                .param("now", now)
                .param("from", Timestamp.from(start))
                .param("to", Timestamp.from(end))
                .update();
        jdbc.sql("""
                INSERT INTO pnh_notification_stats_hourly (bucket_hour, channel_type, client_id, status, count, recomputed_at)
                SELECT date_trunc('hour', created_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC', coalesce(channel_type, 'UNKNOWN'),
                       client_id, status, count(*), :now
                FROM pnh_notifications_history
                WHERE created_at >= :from AND created_at < :to
                GROUP BY 1, 2, 3, 4
                ON CONFLICT (bucket_hour, channel_type, client_id, status)
                DO UPDATE SET count = EXCLUDED.count, recomputed_at = EXCLUDED.recomputed_at""")
                .param("now", now)
                .param("from", Timestamp.from(start))
                .param("to", Timestamp.from(end))
                .update();
    }
}
