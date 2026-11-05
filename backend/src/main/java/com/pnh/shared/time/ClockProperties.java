package com.pnh.shared.time;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code pnh.clock.*} — startup time mode (research R29). */
@ConfigurationProperties("pnh.clock")
public record ClockProperties(ClockMode mode, Instant fixedInstant, String offset, boolean overrideEnabled) {

    public ClockProperties {
        mode = mode == null ? ClockMode.SYSTEM : mode;
    }

    public ClockProperties validate() {
        if (mode == ClockMode.FIXED && fixedInstant == null) {
            throw new IllegalStateException("pnh.clock.mode=fixed requires pnh.clock.fixed-instant");
        }
        if (mode == ClockMode.OFFSET) {
            if (offset == null || offset.isBlank()) {
                throw new IllegalStateException("pnh.clock.mode=offset requires pnh.clock.offset");
            }
            parseOffset(offset);
        }
        return this;
    }

    public Duration offsetDuration() {
        return offset == null || offset.isBlank() ? Duration.ZERO : parseOffset(offset);
    }

    static Duration parseOffset(String value) {
        try {
            return Duration.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    "pnh.clock.offset must be an ISO-8601 duration (e.g. P30D, -PT2H): " + value, e);
        }
    }
}
