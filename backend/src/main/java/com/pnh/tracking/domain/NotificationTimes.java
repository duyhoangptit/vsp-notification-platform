package com.pnh.tracking.domain;

import com.pnh.shared.domain.NotificationIdGenerator;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code created_at} of a notification is the millisecond timestamp embedded in its UUIDv7 id (both come from the
 * Clock bean). Lookups by id therefore know the partition key exactly → single-partition access.
 */
public final class NotificationTimes {

    private NotificationTimes() {}

    public static Instant createdAt(UUID notificationId) {
        return Instant.ofEpochMilli(NotificationIdGenerator.timestampMillis(notificationId));
    }
}
