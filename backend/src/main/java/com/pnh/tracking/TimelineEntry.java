package com.pnh.tracking;

import com.pnh.shared.domain.ErrorCode;
import java.util.UUID;

/** Non-transition timeline event (attempt failures, sanitization, opt-out override, ignored callbacks…). */
public record TimelineEntry(
        UUID notificationId,
        TimelineEventType eventType,
        Integer attempt,
        String providerKey,
        ErrorCode errorCode,
        String errorMessage,
        String providerResponseJson) {

    public static TimelineEntry of(UUID id, TimelineEventType type) {
        return new TimelineEntry(id, type, null, null, null, null, null);
    }
}
