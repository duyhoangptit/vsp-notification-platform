package com.pnh.provider;

import java.util.UUID;

/** OTP lane variant of {@link NotificationReadyForDelivery} (research R6). */
public record OtpNotificationReadyForDelivery(UUID notificationId, String channelType) {}
