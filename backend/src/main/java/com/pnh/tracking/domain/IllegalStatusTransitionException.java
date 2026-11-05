package com.pnh.tracking.domain;

import com.pnh.shared.domain.NotificationStatus;

public class IllegalStatusTransitionException extends RuntimeException {
    public IllegalStatusTransitionException(NotificationStatus from, NotificationStatus to, TransitionTrigger trigger) {
        super("Illegal notification status transition " + from + " -> " + to + " (" + trigger + ")");
    }
}
