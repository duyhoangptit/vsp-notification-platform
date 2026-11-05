package com.pnh.shared.infra;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide single-runner for scheduled jobs (R22): Redisson RLock with watchdog; after 3 consecutive Redis
 * failures falls back to a PostgreSQL advisory lock so the sweeper and alerting keep running.
 */
@Component
public class DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);
    static final int FALLBACK_AFTER_FAILURES = 3;

    private final RedissonClient redisson;
    private final DataSource dataSource;
    private final RedisAvailability availability;
    private int jobLockFailures;
    private boolean advisoryMode;

    public DistributedLockService(RedissonClient redisson, DataSource dataSource, RedisAvailability availability) {
        this.redisson = redisson;
        this.dataSource = dataSource;
        this.availability = availability;
    }

    /** Runs {@code job} if no other instance holds the lock; returns whether it ran. */
    public boolean tryRunExclusive(String jobName, Runnable job) {
        RLock lock;
        boolean acquired;
        try {
            lock = redisson.getLock("lock:job:" + jobName);
            acquired = lock.tryLock(0, -1, TimeUnit.MILLISECONDS);
            availability.markSuccess();
            synchronized (this) {
                jobLockFailures = 0;
                if (advisoryMode) {
                    advisoryMode = false;
                    log.info("Redis job locks available again; leaving PostgreSQL advisory-lock fallback");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            availability.markFailure(e);
            int failures;
            boolean entering;
            synchronized (this) {
                failures = ++jobLockFailures;
                entering = failures >= FALLBACK_AFTER_FAILURES && !advisoryMode;
                if (entering) {
                    advisoryMode = true;
                }
            }
            if (failures >= FALLBACK_AFTER_FAILURES) {
                if (entering) {
                    log.warn(
                            "Redis job locks unavailable after {} consecutive failures; scheduled jobs now run under "
                                    + "PostgreSQL advisory locks (first: {})",
                            failures,
                            jobName);
                }
                return runWithAdvisoryLock(jobName, job);
            }
            log.warn("Skipping job {}: Redis lock unavailable ({} consecutive failures)", jobName, failures);
            return false;
        }
        if (!acquired) {
            return false;
        }
        try {
            job.run();
            return true;
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException e) {
                availability.markFailure(e); // lease expires by itself
            }
        }
    }

    boolean runWithAdvisoryLock(String jobName, Runnable job) {
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(true);
            try {
                if (!advisory(c, "SELECT pg_try_advisory_lock(hashtext(?))", jobName)) {
                    return false;
                }
                try {
                    job.run();
                    return true;
                } finally {
                    advisory(c, "SELECT pg_advisory_unlock(hashtext(?))", jobName);
                }
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.error("Advisory lock fallback failed for job {}", jobName, e);
            return false;
        }
    }

    private static boolean advisory(Connection c, String sql, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "pnh:job:" + name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * Serializes work on one notification across instances (callbacks vs sweeper, R22). Correctness never depends
     * on it — status updates are conditional — so on Redis failure the work proceeds unlocked.
     */
    public <T> T withNotificationLock(String notificationId, Supplier<T> work) {
        RLock lock = null;
        try {
            lock = redisson.getLock("lock:notif:" + notificationId);
            if (!lock.tryLock(10, 10, TimeUnit.SECONDS)) {
                lock = null;
            }
            availability.markSuccess();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lock = null;
        } catch (RuntimeException e) {
            availability.markFailure(e);
            lock = null;
        }
        try {
            return work.get();
        } finally {
            if (lock != null) {
                try {
                    lock.unlock();
                } catch (RuntimeException ignored) {
                    // lease expires
                }
            }
        }
    }
}
