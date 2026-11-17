package com.pnh.tracking.adapter.out;

import com.pnh.shared.infra.DistributedLockService;
import com.pnh.shared.time.ClockMode;
import com.pnh.shared.time.PnhClock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 12-month retention (T177, R9): detaches and drops monthly history/event partitions entirely older than the cutoff and
 * deletes old idempotency keys in batches. Cutoff from the Clock bean; while the clock is overridden it does nothing
 * unless {@code pnh.retention.skip-when-clock-overridden=false} (R29 fix H3). Lock-guarded single runner.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    private static final Pattern PARTITION =
            Pattern.compile("^(pnh_notifications_history|pnh_notification_events)_y(\\d{4})m(\\d{2})$");
    static final int KEY_BATCH = 10_000;

    public record Result(boolean ran, List<String> droppedPartitions, long deletedKeys) {}

    private final JdbcClient jdbc;
    private final DistributedLockService locks;
    private final PnhClock clock;
    private final int months;
    private final boolean skipWhenOverridden;
    private final Counter skipped;
    private final boolean jobsEnabled;

    public RetentionJob(
            JdbcClient jdbc,
            DistributedLockService locks,
            PnhClock clock,
            MeterRegistry meters,
            @Value("${pnh.retention.months:12}") int months,
            @Value("${pnh.retention.skip-when-clock-overridden:true}") boolean skipWhenOverridden,
            @Value("${pnh.jobs.enabled:true}") boolean jobsEnabled) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.clock = clock;
        this.months = months;
        this.skipWhenOverridden = skipWhenOverridden;
        this.jobsEnabled = jobsEnabled;
        this.skipped = Counter.builder("pnh.retention.skipped")
                .tag("reason", "clock_override")
                .description("Retention runs skipped because the system time is overridden")
                .register(meters);
    }

    @Scheduled(cron = "${pnh.retention.cron:0 30 2 1 * *}", zone = "UTC")
    void scheduled() {
        if (jobsEnabled) {
            runNow();
        }
    }

    public Result runNow() {
        Result[] out = {new Result(false, List.of(), 0)};
        locks.tryRunExclusive("retention", () -> out[0] = purge());
        return out[0];
    }

    private Result purge() {
        if (clock.mode() != ClockMode.SYSTEM && skipWhenOverridden) {
            log.warn(
                    "Retention skipped: system time is overridden ({}); set pnh.retention.skip-when-clock-overridden=false to run",
                    clock.mode());
            skipped.increment();
            return new Result(false, List.of(), 0);
        }
        Instant now = clock.instant();
        YearMonth cutoff = YearMonth.from(now.atZone(ZoneOffset.UTC)).minusMonths(months);
        List<String> partitions = jdbc.sql("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname IN ('pnh_notifications_history', 'pnh_notification_events')""").query(String.class).list();
        List<String> dropped = new java.util.ArrayList<>();
        for (String name : partitions) {
            Matcher m = PARTITION.matcher(name);
            if (!m.matches()) {
                continue;
            }
            YearMonth month = YearMonth.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            if (month.isBefore(cutoff)) {
                // identifiers come from the catalogue and match the strict pattern above
                jdbc.sql("ALTER TABLE " + m.group(1) + " DETACH PARTITION " + name)
                        .update();
                jdbc.sql("DROP TABLE " + name).update();
                dropped.add(name);
            }
        }
        Instant keyCutoff = cutoff.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        long deleted = 0;
        int n;
        do {
            n = jdbc.sql("""
                    DELETE FROM pnh_request_keys WHERE ctid IN (
                      SELECT ctid FROM pnh_request_keys WHERE created_at < :cutoff LIMIT :batch)""")
                    .param("cutoff", Timestamp.from(keyCutoff))
                    .param("batch", KEY_BATCH)
                    .update();
            deleted += n;
        } while (n == KEY_BATCH);
        if (!dropped.isEmpty() || deleted > 0) {
            log.info("Retention dropped partitions {} and {} idempotency keys older than {}", dropped, deleted, cutoff);
        }
        return new Result(true, dropped, deleted);
    }
}
