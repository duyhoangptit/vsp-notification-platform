package com.pnh.tracking;

import com.pnh.shared.domain.ErrorCode;

/**
 * A provider delivery report whose authenticity was already verified by the provider adapter.
 * Matching: {@code notificationId} echoed by the provider (preferred), else (providerKey, providerMessageId).
 */
public record DeliveryReport(
        String providerKey,
        String notificationId,
        String providerMessageId,
        Outcome outcome,
        ErrorCode errorCode,
        String errorMessage,
        String rawJson) {

    public enum Outcome {
        DELIVERED,
        FAILED,
        /** Informational (e.g. sms:sent) — timeline only. */
        INFO
    }
}
