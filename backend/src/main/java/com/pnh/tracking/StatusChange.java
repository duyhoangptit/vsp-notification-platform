package com.pnh.tracking;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationStatus;
import java.util.UUID;

/**
 * Request to move a notification from {@code expected} to {@code to}. Applied only if the stored status still equals
 * {@code expected} (and, for FR-041, the stored error code equals {@code expectedErrorCode}).
 */
public record StatusChange(
        UUID notificationId,
        NotificationStatus expected,
        ErrorCode expectedErrorCode,
        NotificationStatus to,
        boolean verifiedCallback,
        ErrorCode errorCode,
        String errorMessage,
        String providerKey,
        String providerMessageId,
        Integer retryCount,
        boolean markSendingStarted,
        TimelineEventType eventType,
        Integer attempt,
        String providerResponseJson) {

    public static Builder of(UUID id, NotificationStatus expected, NotificationStatus to) {
        return new Builder(id, expected, to);
    }

    public static final class Builder {
        private final UUID id;
        private final NotificationStatus expected;
        private final NotificationStatus to;
        private ErrorCode expectedErrorCode;
        private boolean verifiedCallback;
        private ErrorCode errorCode;
        private String errorMessage;
        private String providerKey;
        private String providerMessageId;
        private Integer retryCount;
        private boolean markSendingStarted;
        private TimelineEventType eventType = TimelineEventType.STATUS_CHANGED;
        private Integer attempt;
        private String providerResponseJson;

        private Builder(UUID id, NotificationStatus expected, NotificationStatus to) {
            this.id = id;
            this.expected = expected;
            this.to = to;
        }

        public Builder expectedErrorCode(ErrorCode v) {
            this.expectedErrorCode = v;
            return this;
        }

        public Builder verifiedCallback() {
            this.verifiedCallback = true;
            return this;
        }

        public Builder error(ErrorCode code, String message) {
            this.errorCode = code;
            this.errorMessage = message;
            return this;
        }

        public Builder provider(String key, String messageId) {
            this.providerKey = key;
            this.providerMessageId = messageId;
            return this;
        }

        public Builder retryCount(int v) {
            this.retryCount = v;
            return this;
        }

        public Builder markSendingStarted() {
            this.markSendingStarted = true;
            return this;
        }

        public Builder eventType(TimelineEventType v) {
            this.eventType = v;
            return this;
        }

        public Builder attempt(Integer v) {
            this.attempt = v;
            return this;
        }

        public Builder providerResponse(String json) {
            this.providerResponseJson = json;
            return this;
        }

        public StatusChange build() {
            return new StatusChange(
                    id,
                    expected,
                    expectedErrorCode,
                    to,
                    verifiedCallback,
                    errorCode,
                    errorMessage,
                    providerKey,
                    providerMessageId,
                    retryCount,
                    markSendingStarted,
                    eventType,
                    attempt,
                    providerResponseJson);
        }
    }
}
