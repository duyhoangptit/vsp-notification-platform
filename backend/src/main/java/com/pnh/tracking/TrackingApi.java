package com.pnh.tracking;

import java.util.Optional;
import java.util.UUID;

/** Published API of the tracking module — the only writer of notification history (plan: status ownership). */
public interface TrackingApi {

    /** Inserts the notification in RECEIVED (same transaction as the caller's idempotency key). */
    NotificationView createReceived(NewNotification notification);

    /** Inserts a FAILED notification for a rejected ingest message and publishes its FAILED outcome (fix U1). */
    NotificationView createFailed(NewNotification notification, com.pnh.shared.domain.ErrorCode code, String message);

    /** Conditional forward-only transition + timeline event + outcome publication, atomically. */
    TransitionResult transition(StatusChange change);

    /**
     * Records the provider's send acknowledgement: SENDING → SENT, or — when a verified callback already moved the
     * notification to DELIVERED/FAILED — stores only provider key/message id and publishes nothing (fix I4).
     */
    TransitionResult completeSend(UUID notificationId, String providerKey, String providerMessageId, int retryCount);

    void record(TimelineEntry entry);

    /** Stores the retry count and refreshes sending_started_at while SENDING (sweeper input, R23). */
    void recordRetry(UUID notificationId, int retryCount);

    void markOptOutOverridden(UUID notificationId);

    /** Applies a verified provider delivery report (callbacks; forward-only except FR-041; idempotent). */
    ReportResult applyDeliveryReport(DeliveryReport report);

    /** Records an unverifiable callback for the matching notification (no state change). */
    void recordIgnoredCallback(String providerKey, String notificationId, String providerMessageId, String reason);

    /** Outcome counts per channel for notifications updated since {@code since} (alert Postgres fallback). */
    java.util.List<ChannelOutcomeCounts> outcomeCounts(java.time.Instant since);

    Optional<NotificationView> findById(UUID notificationId);

    Optional<NotificationView> findByClientRequest(String clientId, String clientRequestId);

    Optional<NotificationView> findByProviderMessage(String providerKey, String providerMessageId);
}
