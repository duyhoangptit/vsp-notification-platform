package com.pnh.tracking.domain;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.tracking.TimelineEventType;
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
