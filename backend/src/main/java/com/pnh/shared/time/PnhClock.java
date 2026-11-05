package com.pnh.shared.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The single application time source (constitution VI, research R29). UTC, switchable at runtime between
 * SYSTEM, FIXED(instant) and OFFSET(duration); the switch is a single atomic reference swap.
 */
public final class PnhClock extends Clock {

    private record State(ClockMode mode, Instant fixed, Duration offset) {}

    private static final State SYSTEM = new State(ClockMode.SYSTEM, null, Duration.ZERO);

    private final Clock base;
    private final AtomicReference<State> state = new AtomicReference<>(SYSTEM);

    public PnhClock(Clock base) {
        this.base = Objects.requireNonNull(base).withZone(ZoneOffset.UTC);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        PnhClock self = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return self.withZone(other);
            }

            @Override
            public Instant instant() {
                return self.instant();
            }
        };
    }

    @Override
    public Instant instant() {
        State s = state.get();
        return switch (s.mode()) {
            case SYSTEM -> base.instant();
            case FIXED -> s.fixed();
            case OFFSET -> base.instant().plus(s.offset());
        };
    }

    @Override
    public long millis() {
        return instant().toEpochMilli();
    }

    /** Real (non-overridden) time, for display in the console only. */
    public Instant realInstant() {
        return base.instant();
    }

    public ClockMode mode() {
        return state.get().mode();
    }

    public Instant fixedInstant() {
        return state.get().fixed();
    }

    public Duration offset() {
        return state.get().offset();
    }

    public void setFixed(Instant instant) {
        state.set(new State(ClockMode.FIXED, Objects.requireNonNull(instant), Duration.ZERO));
    }

    public void setOffset(Duration offset) {
        state.set(new State(ClockMode.OFFSET, null, Objects.requireNonNull(offset)));
    }

    public void reset() {
        state.set(SYSTEM);
    }

    public void apply(ClockProperties props) {
        props.validate();
        switch (props.mode()) {
            case SYSTEM -> reset();
            case FIXED -> setFixed(props.fixedInstant());
            case OFFSET -> setOffset(props.offsetDuration());
        }
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
