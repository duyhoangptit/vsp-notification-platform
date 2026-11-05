package com.pnh.shared.time;

import java.time.Instant;

/** Console representation (console-api ClockState). */
public record ClockState(
        ClockMode mode,
        Instant now,
        Instant realNow,
        Instant fixedInstant,
        String offset,
        boolean overrideEnabled,
        String changedBy,
        Instant changedAt) {}
