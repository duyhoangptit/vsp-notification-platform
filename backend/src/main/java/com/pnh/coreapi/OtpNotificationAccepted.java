package com.pnh.coreapi;

import java.util.UUID;

/** OTP lane variant of {@link NotificationAccepted}, handled on the dedicated OTP executor (research R6). */
public record OtpNotificationAccepted(UUID notificationId, String userId, String channelType) {}
