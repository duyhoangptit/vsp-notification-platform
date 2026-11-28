package com.vsp.notification.coreapi.application;

import com.vsp.notification.shared.domain.Category;
import com.vsp.notification.shared.domain.ContentMode;
import java.time.Instant;
import java.util.Map;

/** Result of intake validation + rendering: everything needed to persist the notification. */
public record ValidatedNotification(
        String clientId,
        String clientRequestId,
        String userId,
        String channelType,
        String recipient,
        Category category,
        ContentMode contentMode,
        String templateCode,
        Long templateVersion,
        String subject,
        String title,
        String body,
        Map<String, String> pushData,
        Instant expiresAt,
        String otpValue,
        boolean sanitized) {

    @Override
    public String toString() {
        return "ValidatedNotification[" + clientId + "/" + clientRequestId + ", " + channelType + ", " + category + "]";
    }
}
