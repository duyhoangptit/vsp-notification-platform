package com.pnh.tracking;

import com.pnh.shared.domain.NotificationStatus;

/** Outcome of a conditional transition; {@code applied=false} means another actor changed the status first. */
public record TransitionResult(boolean applied, NotificationStatus currentStatus, int resultSequence) {}
