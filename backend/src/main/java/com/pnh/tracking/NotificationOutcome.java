package com.pnh.tracking;

import java.time.Instant;
import java.util.UUID;

/** Published when a notification enters SENT, DELIVERED or FAILED (once per outcome, R26). */
public record NotificationOutcome(
        UUID notificationId,
        String clientId,
        String clientRequestId,
        String userId,
        String channelType,
        String category,
        String status,
        String errorCode,
        String errorMessage,
        int sequence,
        Instant createdAt,
        Instant occurredAt) {}
