package com.pnh.support;

import com.pnh.shared.domain.Category;
import com.pnh.shared.domain.ContentMode;
import com.pnh.shared.domain.NotificationIdGenerator;
import com.pnh.tracking.NewNotification;
import java.time.Instant;
import java.util.UUID;

public final class NotificationFixtures {

    private NotificationFixtures() {}

    public static NewNotification email(NotificationIdGenerator ids) {
        return email(ids.next(), "client-x", "req-" + UUID.randomUUID(), "user-1");
    }

    public static NewNotification email(UUID id, String clientId, String requestId, String userId) {
        return new NewNotification(
                id,
                clientId,
                requestId,
                userId,
                "user@example.com",
                "EMAIL",
                Category.STANDARD,
                ContentMode.TEMPLATE,
                "NEWSLETTER_EMAIL",
                0L,
                "Hello",
                null,
                "<p>Hello</p>",
                new byte[] {1, 2, 3},
                null,
                null);
    }

    public static NewNotification otpSms(UUID id, String clientId, String requestId, Instant expiresAt) {
        return new NewNotification(
                id,
                clientId,
                requestId,
                "user-1",
                "+84901234567",
                "SMS",
                Category.OTP,
                ContentMode.DIRECT,
                null,
                null,
                null,
                null,
                "Code ******",
                new byte[] {9},
                null,
                expiresAt);
    }
}
