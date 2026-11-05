package com.pnh.tracking;

import com.pnh.shared.domain.Category;
import com.pnh.shared.domain.ContentMode;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Read model of one notification (published API of the tracking module). */
public record NotificationView(
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
        Instant expiresAt,
        boolean optOutOverridden,
        NotificationStatus status,
        int retryCount,
        String providerKey,
        String providerMessageId,
        ErrorCode errorCode,
        String errorMessage,
        int resultSequence,
        Instant sendingStartedAt,
        Instant createdAt,
        Instant updatedAt) {

    @Override
    public String toString() {
        return "NotificationView[" + notificationId + ", status=" + status + "]";
    }
}
