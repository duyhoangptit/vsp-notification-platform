package com.pnh.tracking.domain;

/** Who asks for a status change: the hub itself, or a provider callback whose signature was verified. */
public enum TransitionTrigger {
    INTERNAL,
    VERIFIED_CALLBACK
}
