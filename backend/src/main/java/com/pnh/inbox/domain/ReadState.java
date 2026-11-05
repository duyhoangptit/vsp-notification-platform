package com.pnh.inbox.domain;

import java.time.Instant;
import java.util.UUID;

/** Inbox read state of a PUSH STANDARD notification (FR-019: first read time preserved). */
public record ReadState(
        UUID notificationId, String userId, boolean read, Instant readAt, Instant notificationCreatedAt) {}
