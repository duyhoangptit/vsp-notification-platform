package com.pnh.tracking;

/** Timeline event types of pnh_notification_events (data-model). */
public enum TimelineEventType {
    STATUS_CHANGED,
    ATTEMPT_FAILED,
    OPT_OUT_OVERRIDDEN,
    CALLBACK_RECEIVED,
    CALLBACK_IGNORED,
    SWEPT,
    RESUBMISSION_SKIPPED,
    CONTENT_SANITIZED
}
