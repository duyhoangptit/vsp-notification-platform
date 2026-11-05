package com.pnh.provider;

import java.util.UUID;

/** Inbound command of the provider module (published by the dispatcher): a notification passed routing/preferences (PROCESSED) and is ready for its channel provider. */
public record NotificationReadyForDelivery(UUID notificationId, String channelType, String category) {}
