package com.pnh.tracking.application;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.shared.infra.DistributedLockService;
import com.pnh.tracking.DeliveryReport;
import com.pnh.tracking.NotificationView;
import com.pnh.tracking.ReportResult;
import com.pnh.tracking.StatusChange;
import com.pnh.tracking.TimelineEntry;
import com.pnh.tracking.TimelineEventType;
import com.pnh.tracking.TransitionResult;
import com.pnh.tracking.adapter.out.NotificationJdbcRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verified provider delivery reports (T106): matched by echoed notification id, else (provider_key, message id);
 * serialized per notification ({@code lock:notif:{id}}) + conditional update; DELIVERED/FAILED accepted from
 * SENDING or SENT (fix I4), FAILED(DELIVERY_STATE_UNKNOWN) → DELIVERED (FR-041); everything else is ignored.
 */
@Service
public class DeliveryReportService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryReportService.class);

    private final NotificationJdbcRepository repository;
    private final StatusTransitionService transitions;
    private final DistributedLockService locks;
    private final TransactionTemplate tx;

    public DeliveryReportService(
            NotificationJdbcRepository repository,
            StatusTransitionService transitions,
            DistributedLockService locks,
            TransactionTemplate tx) {
        this.repository = repository;
        this.transitions = transitions;
        this.locks = locks;
        this.tx = tx;
    }

    public ReportResult apply(DeliveryReport report) {
        Optional<NotificationView> match =
                match(report.providerKey(), report.notificationId(), report.providerMessageId());
        if (match.isEmpty()) {
            log.info("Delivery report from {} matched no notification", report.providerKey());
            return ReportResult.NOT_FOUND;
        }
        UUID id = match.get().notificationId();
        return locks.withNotificationLock(id.toString(), () -> tx.execute(s -> applyLocked(id, report)));
    }

    private ReportResult applyLocked(UUID id, DeliveryReport report) {
        NotificationView n = repository.findById(id).orElseThrow();
        if (report.outcome() == DeliveryReport.Outcome.INFO) {
            transitions.record(new TimelineEntry(
                    id, TimelineEventType.CALLBACK_RECEIVED, null, report.providerKey(), null, null, report.rawJson()));
            return ReportResult.APPLIED;
        }
        NotificationStatus target = report.outcome() == DeliveryReport.Outcome.DELIVERED
                ? NotificationStatus.DELIVERED
                : NotificationStatus.FAILED;
        boolean allowed = switch (n.status()) {
            case SENDING, SENT -> true;
            case FAILED -> target == NotificationStatus.DELIVERED && n.errorCode() == ErrorCode.DELIVERY_STATE_UNKNOWN;
            default -> false;
        };
        if (!allowed) {
            ignore(id, report.providerKey(), "status " + n.status() + " does not accept " + target);
            return ReportResult.IGNORED;
        }
        StatusChange.Builder change = StatusChange.of(id, n.status(), target)
                .verifiedCallback()
                .eventType(TimelineEventType.CALLBACK_RECEIVED)
                .providerResponse(report.rawJson())
                .provider(report.providerKey(), n.providerMessageId() == null ? report.providerMessageId() : null);
        if (n.status() == NotificationStatus.FAILED) {
            change.expectedErrorCode(ErrorCode.DELIVERY_STATE_UNKNOWN);
        }
        if (target == NotificationStatus.FAILED) {
            change.error(
                    report.errorCode() == null ? ErrorCode.PROVIDER_REJECTED : report.errorCode(),
                    report.errorMessage());
        }
        TransitionResult r = transitions.transition(change.build());
        if (!r.applied()) {
            ignore(id, report.providerKey(), "concurrent status change");
            return ReportResult.IGNORED;
        }
        log.info("Delivery report applied: {} -> {}", n.status(), target);
        return ReportResult.APPLIED;
    }

    public void ignored(String providerKey, String notificationId, String providerMessageId, String reason) {
        Optional<NotificationView> n = match(providerKey, notificationId, providerMessageId);
        if (n.isPresent()) {
            ignore(n.get().notificationId(), providerKey, reason);
        } else {
            // FR-017: unmatched/unverifiable callbacks have no timeline to land on — keep an operational trace
            log.warn("CALLBACK_IGNORED provider={} (no matching notification): {}", providerKey, reason);
        }
    }

    private void ignore(UUID id, String providerKey, String reason) {
        log.warn("CALLBACK_IGNORED provider={} notification={}: {}", providerKey, id, reason);
        transitions.record(
                new TimelineEntry(id, TimelineEventType.CALLBACK_IGNORED, null, providerKey, null, reason, null));
    }

    private Optional<NotificationView> match(String providerKey, String notificationId, String providerMessageId) {
        if (notificationId != null) {
            try {
                Optional<NotificationView> byId = repository.findById(UUID.fromString(notificationId));
                if (byId.isPresent()) {
                    return byId;
                }
            } catch (IllegalArgumentException ignored) {
                // not a hub id
            }
        }
        if (providerMessageId != null) {
            Optional<NotificationView> byMsg = repository.findByProviderMessage(providerKey, providerMessageId);
            if (byMsg.isPresent()) {
                return byMsg;
            }
            try { // gateways that echo our id as their message id
                return repository.findById(UUID.fromString(providerMessageId));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
