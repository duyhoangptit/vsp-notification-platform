package com.pnh.shared.domain;

public enum NotificationStatus {
    RECEIVED,
    PROCESSED,
    SENDING,
    SENT,
    DELIVERED,
    FAILED;

    public boolean isTerminal() {
        return this == DELIVERED || this == FAILED;
    }

    /** Statuses that produce a result message (FR-016). */
    public boolean isOutcome() {
        return this == SENT || this == DELIVERED || this == FAILED;
    }
}
