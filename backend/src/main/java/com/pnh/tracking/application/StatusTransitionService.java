package com.pnh.tracking.application;

import com.pnh.shared.config.PnhMetrics;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.tracking.NewNotification;
import com.pnh.tracking.NotificationOutcome;
import com.pnh.tracking.NotificationView;
import com.pnh.tracking.StatusChange;
import com.pnh.tracking.TimelineEntry;
import com.pnh.tracking.TimelineEventType;
import com.pnh.tracking.TrackingApi;
import com.pnh.tracking.TransitionResult;
import com.pnh.tracking.adapter.out.NotificationJdbcRepository;
import com.pnh.tracking.domain.NotificationEvent;
import com.pnh.tracking.domain.NotificationStatusMachine;
import com.pnh.tracking.domain.TransitionTrigger;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single writer of notification status (T064): conditional UPDATE … WHERE status = :expected, timeline event in the
 * same transaction, result sequence + {@link NotificationOutcome} on SENT/DELIVERED/FAILED.
 */
@Service
public class StatusTransitionService implements TrackingApi {

    private static final Logger log = LoggerFactory.getLogger(StatusTransitionService.class);

    private final NotificationJdbcRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final PnhMetrics metrics;
    private final org.springframework.beans.factory.ObjectProvider<DeliveryReportService> reports;

    public StatusTransitionService(
            NotificationJdbcRepository repository,
            ApplicationEventPublisher events,
            Clock clock,
            PnhMetrics metrics,
            org.springframework.beans.factory.ObjectProvider<DeliveryReportService> reports) {
        this.reports = reports;
        this.repository = repository;
        this.events = events;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public NotificationView createReceived(NewNotification n) {
        Instant now = clock.instant();
        repository.insert(n, NotificationStatus.RECEIVED, null, null, 0, now);
        repository.insertEvent(new NotificationEvent(
                null,
                n.notificationId(),
                now,
                TimelineEventType.STATUS_CHANGED,
                null,
                NotificationStatus.RECEIVED,
                null,
                null,
                null,
                null,
                null));
        metrics.notificationStatus(n.channelType(), NotificationStatus.RECEIVED.name());
        return repository.findById(n.notificationId()).orElseThrow();
    }

    @Override
    @Transactional
    public NotificationView createFailed(NewNotification n, ErrorCode code, String message) {
        Instant now = clock.instant();
        repository.insert(n, NotificationStatus.FAILED, code, message, 1, now);
        repository.insertEvent(new NotificationEvent(
                null,
                n.notificationId(),
                now,
                TimelineEventType.STATUS_CHANGED,
                null,
                NotificationStatus.FAILED,
                null,
                null,
                code,
                message,
                null));
        NotificationView view = repository.findById(n.notificationId()).orElseThrow();
        publishOutcome(view, now);
        return view;
    }

    @Override
    @Transactional
    public TransitionResult transition(StatusChange c) {
        TransitionTrigger trigger =
                c.verifiedCallback() ? TransitionTrigger.VERIFIED_CALLBACK : TransitionTrigger.INTERNAL;
        if (!NotificationStatusMachine.canTransition(c.expected(), c.expectedErrorCode(), c.to(), trigger)) {
            NotificationStatusMachine.require(c.expected(), c.expectedErrorCode(), c.to(), trigger);
        }
        Instant now = clock.instant();
        Optional<NotificationView> updated = repository.applyTransition(c, now);
        if (updated.isEmpty()) {
            NotificationStatus current = repository
                    .findById(c.notificationId())
                    .map(NotificationView::status)
                    .orElse(null);
            log.debug(
                    "Transition {} -> {} not applied for {} (current {})",
                    c.expected(),
                    c.to(),
                    c.notificationId(),
                    current);
            return new TransitionResult(false, current, -1);
        }
        NotificationView view = updated.get();
        repository.insertEvent(new NotificationEvent(
                null,
                c.notificationId(),
                now,
                c.eventType(),
                c.expected(),
                c.to(),
                c.attempt(),
                c.providerKey(),
                c.errorCode(),
                c.errorMessage(),
                c.providerResponseJson()));
        metrics.notificationStatus(view.channelType(), c.to().name());
        if (c.to().isOutcome()) {
            publishOutcome(view, now);
        }
        return new TransitionResult(true, view.status(), view.resultSequence());
    }

    @Override
    @Transactional
    public TransitionResult completeSend(UUID id, String providerKey, String providerMessageId, int retryCount) {
        TransitionResult result = transition(StatusChange.of(id, NotificationStatus.SENDING, NotificationStatus.SENT)
                .provider(providerKey, providerMessageId)
                .retryCount(retryCount)
                .build());
        if (!result.applied()
                && result.currentStatus() != null
                && result.currentStatus().isTerminal()) {
            repository.storeProviderIdsIfFinished(id, providerKey, providerMessageId, clock.instant());
        }
        return result;
    }

    @Override
    @Transactional
    public void record(TimelineEntry e) {
        repository.insertEvent(new NotificationEvent(
                null,
                e.notificationId(),
                clock.instant(),
                e.eventType(),
                null,
                null,
                e.attempt(),
                e.providerKey(),
                e.errorCode(),
                e.errorMessage(),
                e.providerResponseJson()));
    }

    @Override
    @Transactional
    public void markOptOutOverridden(UUID notificationId) {
        repository.markOptOutOverridden(notificationId);
        record(TimelineEntry.of(notificationId, TimelineEventType.OPT_OUT_OVERRIDDEN));
    }

    @Override
    @Transactional
    public void recordRetry(UUID id, int retryCount) {
        repository.updateRetryCount(id, retryCount, clock.instant());
    }

    @Override
    public com.pnh.tracking.ReportResult applyDeliveryReport(com.pnh.tracking.DeliveryReport report) {
        return reports.getObject().apply(report);
    }

    @Override
    public void recordIgnoredCallback(
            String providerKey, String notificationId, String providerMessageId, String reason) {
        reports.getObject().ignored(providerKey, notificationId, providerMessageId, reason);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.pnh.tracking.ChannelOutcomeCounts> outcomeCounts(Instant since) {
        return repository.outcomeCounts(since, clock.instant()); // window ends at the Clock's now (R29)
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationView> findById(UUID notificationId) {
        return repository.findById(notificationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationView> findByClientRequest(String clientId, String clientRequestId) {
        return repository.findByClientRequest(clientId, clientRequestId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationView> findByProviderMessage(String providerKey, String providerMessageId) {
        return repository.findByProviderMessage(providerKey, providerMessageId);
    }

    private void publishOutcome(NotificationView v, Instant now) {
        events.publishEvent(new com.pnh.tracking.NotificationResultEvent(
                java.util.UUID.randomUUID(),
                v.notificationId(),
                v.clientId(),
                v.clientRequestId(),
                v.userId(),
                v.channelType(),
                v.status().name(),
                v.errorCode() == null ? null : v.errorCode().name(),
                v.errorMessage(),
                v.resultSequence(),
                now));
        events.publishEvent(new NotificationOutcome(
                v.notificationId(),
                v.clientId(),
                v.clientRequestId(),
                v.userId(),
                v.channelType(),
                v.category().name(),
                v.status().name(),
                v.errorCode() == null ? null : v.errorCode().name(),
                v.errorMessage(),
                v.resultSequence(),
                v.createdAt(),
                now));
    }
}
