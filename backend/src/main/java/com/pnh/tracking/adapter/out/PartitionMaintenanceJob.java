package com.pnh.tracking.adapter.out;

import com.pnh.shared.infra.DistributedLockService;
import com.pnh.shared.infra.PartitionService;
import com.pnh.shared.time.PnhClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps monthly partitions 3 months ahead (T045), around BOTH the (possibly overridden) clock month and the real
 * month, so inserts never fail for lack of a partition (R29 fix H2). Lock-guarded single runner.
 */
@Component
public class PartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);
    static final int MONTHS_AHEAD = 3;

    private final PartitionService partitions;
    private final DistributedLockService locks;
    private final PnhClock clock;

    public PartitionMaintenanceJob(PartitionService partitions, DistributedLockService locks, PnhClock clock) {
        this.partitions = partitions;
        this.locks = locks;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ensureNow(); // idempotent; safe even if another instance runs concurrently
    }

    @Scheduled(cron = "${pnh.partitions.cron:0 15 3 * * *}", zone = "UTC")
    public void scheduled() {
        run();
    }

    /** @return whether this instance ran the job */
    public boolean run() {
        return locks.tryRunExclusive("partition-maintenance", this::ensureNow);
    }

    void ensureNow() {
        int created = partitions.ensureMonths(clock.instant(), MONTHS_AHEAD);
        created += partitions.ensureMonths(clock.realInstant(), MONTHS_AHEAD);
        if (created > 0) {
            log.info("Created {} monthly partitions", created);
        }
    }
}
