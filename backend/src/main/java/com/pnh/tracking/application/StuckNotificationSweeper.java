package com.pnh.tracking.application;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.shared.infra.DistributedLockService;
import com.pnh.tracking.NotificationStuckFailed;
import com.pnh.tracking.StatusChange;
import com.pnh.tracking.TimelineEventType;
import com.pnh.tracking.TransitionResult;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stuck-notification sweeper (T149, R23, FR-040): never re-sends. SENDING older than the stuck timeout →
 * FAILED/DELIVERY_STATE_UNKNOWN; RECEIVED/PROCESSED older than the processing timeout → FAILED/PROCESSING_TIMEOUT;
 * in-flight OTP past expires_at → FAILED/OTP_EXPIRED. ≤ 500 per run; cutoffs from the Clock bound as parameters.
 */
@Service
public class StuckNotificationSweeper {

    private static final Logger log = LoggerFactory.getLogger(StuckNotificationSweeper.class);
    static final int BATCH = 500;

    private record Candidate(UUID id, NotificationStatus status, String channel) {}

    private final JdbcClient jdbc;
    private final StatusTransitionService transitions;
    private final DistributedLockService locks;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration stuckTimeout;
    private final Duration processingTimeout;
    private final boolean jobsEnabled;

    public StuckNotificationSweeper(
            JdbcClient jdbc,
            StatusTransitionService transitions,
            DistributedLockService locks,
            ApplicationEventPublisher events,
            TransactionTemplate tx,
            Clock clock,
            @Value("${pnh.delivery.stuck-timeout:5m}") Duration stuckTimeout,
            @Value("${pnh.delivery.processing-timeout:15m}") Duration processingTimeout,
            @Value("${pnh.jobs.enabled:true}") boolean jobsEnabled) {
        this.jdbc = jdbc;
        this.transitions = transitions;
        this.locks = locks;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
        this.stuckTimeout = stuckTimeout;
        this.processingTimeout = processingTimeout;
        this.jobsEnabled = jobsEnabled;
    }

    @Scheduled(
            fixedDelayString = "${pnh.sweeper.interval:60s}",
            initialDelayString = "${pnh.sweeper.initial-delay:60s}")
    void scheduled() {
        if (jobsEnabled) {
            locks.tryRunExclusive("stuck-sweeper", this::sweepNow);
        }
    }

    /** One run; returns the number of notifications failed. */
    public int sweepNow() {
        Instant now = clock.instant();
        AtomicInteger done = new AtomicInteger();
        // 1. in-flight OTPs past their expiry
        sweep(candidates("""
                SELECT notification_id, status, channel_type FROM pnh_notifications_history
                WHERE status IN ('RECEIVED','PROCESSED','SENDING') AND expires_at IS NOT NULL AND expires_at < :now
                ORDER BY updated_at LIMIT :limit""", now, BATCH), ErrorCode.OTP_EXPIRED, "OTP expired before delivery", done);
        // 2. SENDING with unknown outcome
        sweep(
                candidates("""
                SELECT notification_id, status, channel_type FROM pnh_notifications_history
                WHERE status = 'SENDING' AND updated_at < :now ORDER BY updated_at LIMIT :limit""", now.minus(stuckTimeout), BATCH - done.get()),
                ErrorCode.DELIVERY_STATE_UNKNOWN,
                "No provider outcome within " + stuckTimeout.toMinutes() + " min; not re-sent",
                done);
        // 3. never dispatched
        sweep(
                candidates("""
                SELECT notification_id, status, channel_type FROM pnh_notifications_history
                WHERE status IN ('RECEIVED','PROCESSED') AND updated_at < :now ORDER BY updated_at LIMIT :limit""", now.minus(processingTimeout), BATCH - done.get()),
                ErrorCode.PROCESSING_TIMEOUT,
                "Not processed within " + processingTimeout.toMinutes() + " min",
                done);
        if (done.get() > 0) {
            log.warn("Sweeper failed {} stuck notification(s)", done.get());
        }
        return done.get();
    }

    private List<Candidate> candidates(String sql, Instant cutoff, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.sql(sql)
                .param("now", Timestamp.from(cutoff))
                .param("limit", limit)
                .query((rs, n) -> new Candidate(
                        rs.getObject(1, UUID.class), NotificationStatus.valueOf(rs.getString(2)), rs.getString(3)))
                .list();
    }

    private void sweep(List<Candidate> candidates, ErrorCode code, String message, AtomicInteger done) {
        for (Candidate c : candidates) {
            Boolean applied = locks.withNotificationLock(
                    c.id().toString(),
                    () -> tx.execute(s -> {
                        TransitionResult r =
                                transitions.transition(StatusChange.of(c.id(), c.status(), NotificationStatus.FAILED)
                                        .error(code, message)
                                        .eventType(TimelineEventType.SWEPT)
                                        .build());
                        if (r.applied() && code != ErrorCode.OTP_EXPIRED) {
                            events.publishEvent(
                                    new NotificationStuckFailed(c.id(), c.channel(), code.name(), clock.instant()));
                        }
                        return r.applied();
                    }));
            if (Boolean.TRUE.equals(applied)) {
                done.incrementAndGet();
            }
        }
    }
}
