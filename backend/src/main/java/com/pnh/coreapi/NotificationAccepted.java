package com.pnh.coreapi;

import java.util.UUID;

/** A notification was accepted (RECEIVED) — published in the acceptance transaction (event registry, R4). */
public record NotificationAccepted(UUID notificationId, String userId, String channelType, String category) {}
