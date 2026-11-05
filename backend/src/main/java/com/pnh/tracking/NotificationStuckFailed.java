package com.pnh.tracking;

import java.time.Instant;
import java.util.UUID;

/** The sweeper failed a notification whose outcome stayed unknown (alert signal STUCK_DELIVERY_SPIKE, R23/R24). */
public record NotificationStuckFailed(UUID notificationId, String channelType, String errorCode, Instant occurredAt) {}
