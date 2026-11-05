package com.pnh.tracking;

import com.pnh.shared.domain.Category;
import com.pnh.shared.domain.ContentMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Data of an accepted (or rejected-ingest) notification. Content is already rendered and masked for OTP. */
public record NewNotification(
        UUID notificationId,
        String clientId,
        String clientRequestId,
        String userId,
        String recipientTarget,
        String channelType,
        Category category,
        ContentMode contentMode,
        String templateCode,
        Long templateVersion,
        String subject,
        String title,
        String compiledContent,
        byte[] payloadEncrypted,
        Map<String, String> pushData,
        Instant expiresAt) {

    @Override
    public String toString() {
        return "NewNotification[" + notificationId + ", client=" + clientId + ", request=" + clientRequestId + "]";
    }
}
