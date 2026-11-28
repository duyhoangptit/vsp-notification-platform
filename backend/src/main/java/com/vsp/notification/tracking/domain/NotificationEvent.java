package com.vsp.notification.tracking.domain;

import com.vsp.notification.shared.domain.ErrorCode;
import com.vsp.notification.shared.domain.NotificationStatus;
import com.vsp.notification.tracking.TimelineEventType;
import java.time.Instant;
import java.util.UUID;

/** Row of pnh_notification_events (status timeline, FR-014). */
public record NotificationEvent(
        Long id,
        UUID notificationId,
        Instant createdAt,
        TimelineEventType eventType,
        NotificationStatus fromStatus,
        NotificationStatus toStatus,
        Integer attempt,
        String providerKey,
        ErrorCode errorCode,
        String errorMessage,
        String providerResponseJson) {}
