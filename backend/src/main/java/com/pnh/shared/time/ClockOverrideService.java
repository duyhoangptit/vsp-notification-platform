package com.pnh.shared.time;

import com.pnh.shared.audit.ConfigChanged;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.infra.PartitionService;
import com.pnh.shared.infra.RedisAvailability;
import com.pnh.shared.web.PnhException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runtime system-time override for non-production environments (R29). State lives in Redis key
 * {@code pnh:clock:override} and is broadcast on topic {@code pnh:clock}; partitions for the new month (+3) are
 * created BEFORE the switch (fix H2). Redis down → configured mode stays in effect.
 */
@Service
public class ClockOverrideService {

    public static final String KEY = "pnh:clock:override";
    public static final String TOPIC = "pnh:clock";
    private static final Logger log = LoggerFactory.getLogger(ClockOverrideService.class);

    record StoredState(String mode, Instant fixedInstant, String offset, String changedBy, Instant changedAt) {}

    private final PnhClock clock;
    private final ClockProperties properties;
    private final RedissonClient redisson;
    private final RedisAvailability availability;
    private final PartitionService partitions;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private volatile String changedBy;
    private volatile Instant changedAt;

    public ClockOverrideService(
            PnhClock clock,
            ClockProperties properties,
            RedissonClient redisson,
            RedisAvailability availability,
            PartitionService partitions,
            ApplicationEventPublisher events,
            TransactionTemplate tx,
            JsonMapper json,
            MeterRegistry meters) {
        this.clock = clock;
        this.properties = properties;
        this.redisson = redisson;
        this.availability = availability;
        this.partitions = partitions;
        this.events = events;
        this.tx = tx;
        this.json = json;
        Gauge.builder("pnh.clock.override.active", clock, c -> c.mode() == ClockMode.SYSTEM ? 0 : 1)
                .register(meters);
    }

    public boolean overrideEnabled() {
        return properties.overrideEnabled();
    }

    public ClockState state() {
        Duration offset = clock.mode() == ClockMode.OFFSET ? clock.offset() : null;
        return new ClockState(
                clock.mode(),
                clock.instant(),
                clock.realInstant(),
                clock.fixedInstant(),
                offset == null ? null : offset.toString(),
                properties.overrideEnabled(),
                changedBy,
                changedAt);
    }

    public ClockState override(ClockMode mode, Instant fixedInstant, String offset, String actor) {
        requireEnabled();
        Instant target;
        Duration duration = null;
        switch (mode) {
            case FIXED -> {
                if (fixedInstant == null) {
                    throw PnhException.field(
                            ErrorCode.VALIDATION_ERROR, "fixed_instant", "fixed_instant is required for FIXED");
                }
                target = fixedInstant;
            }
            case OFFSET -> {
                if (offset == null || offset.isBlank()) {
                    throw PnhException.field(ErrorCode.VALIDATION_ERROR, "offset", "offset is required for OFFSET");
                }
                try {
                    duration = Duration.parse(offset.trim());
                } catch (java.time.format.DateTimeParseException e) {
                    throw PnhException.field(
                            ErrorCode.VALIDATION_ERROR, "offset", "offset must be an ISO-8601 duration");
                }
                target = clock.realInstant().plus(duration);
            }
            default -> throw PnhException.field(ErrorCode.VALIDATION_ERROR, "mode", "mode must be FIXED or OFFSET");
        }
        try {
            partitions.ensureMonths(target, 3);
        } catch (RuntimeException e) {
            log.warn("Clock override rejected: partitions for {} could not be created", target, e);
            throw new PnhException(
                    ErrorCode.VALIDATION_ERROR, "Partitions for the requested time could not be created");
        }
        ClockState before = state();
        Instant now = clock.realInstant();
        StoredState stored = new StoredState(
                mode.name(),
                mode == ClockMode.FIXED ? fixedInstant : null,
                duration == null ? null : duration.toString(),
                actor,
                now);
        apply(stored);
        persistAndBroadcast(stored);
        audit(before, actor, now);
        return state();
    }

    public ClockState reset(String actor) {
        requireEnabled();
        ClockState before = state();
        Instant now = clock.realInstant();
        StoredState stored = new StoredState(ClockMode.SYSTEM.name(), null, null, actor, now);
        clock.apply(properties); // configured startup mode (normally SYSTEM)
        changedBy = actor;
        changedAt = now;
        try {
            redisson.getBucket(KEY, StringCodec.INSTANCE).delete();
            topic().publish(json.writeValueAsString(stored));
            availability.markSuccess();
        } catch (RuntimeException e) {
            availability.markFailure(e);
        }
        audit(before, actor, now);
        return state();
    }

    private void requireEnabled() {
        if (!properties.overrideEnabled()) {
            throw new PnhException(ErrorCode.NOT_FOUND, "Clock override is disabled");
        }
    }

    void apply(StoredState s) {
        ClockMode mode = ClockMode.valueOf(s.mode());
        switch (mode) {
            case SYSTEM -> clock.apply(properties);
            case FIXED -> clock.setFixed(s.fixedInstant());
            case OFFSET -> clock.setOffset(Duration.parse(s.offset()));
        }
        changedBy = s.changedBy();
        changedAt = s.changedAt();
    }

    private void persistAndBroadcast(StoredState s) {
        try {
            String payload = json.writeValueAsString(s);
            redisson.getBucket(KEY, StringCodec.INSTANCE).set(payload);
            topic().publish(payload);
            availability.markSuccess();
        } catch (RuntimeException e) {
            availability.markFailure(e);
            log.warn("Clock override applied locally only (Redis unavailable)");
        }
    }

    private void audit(ClockState before, String actor, Instant at) {
        ClockState after = state();
        tx.executeWithoutResult(status -> events.publishEvent(new ConfigChanged(
                "CLOCK_OVERRIDE",
                "clock",
                "UPDATE",
                0,
                snapshot(before),
                snapshot(after),
                Set.of(),
                Set.of(),
                actor,
                at)));
    }

    private static Map<String, Object> snapshot(ClockState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", s.mode().name());
        m.put(
                "fixed_instant",
                s.fixedInstant() == null ? null : s.fixedInstant().toString());
        m.put("offset", s.offset());
        return m;
    }

    private RTopic topic() {
        return redisson.getTopic(TOPIC, StringCodec.INSTANCE);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadAndSubscribe() {
        if (!properties.overrideEnabled()) {
            return;
        }
        try {
            topic().addListener(String.class, (channel, msg) -> {
                try {
                    StoredState s = json.readValue(msg, StoredState.class);
                    if (ClockMode.valueOf(s.mode()) != ClockMode.SYSTEM) {
                        partitions.ensureMonths(targetOf(s), 3);
                    }
                    apply(s);
                } catch (RuntimeException e) {
                    log.warn("Ignoring invalid clock override message", e);
                }
            });
            Object stored = redisson.getBucket(KEY, StringCodec.INSTANCE).get();
            if (stored != null) {
                StoredState s = json.readValue(stored.toString(), StoredState.class);
                partitions.ensureMonths(targetOf(s), 3);
                apply(s);
                log.warn("System time override active: {} -> now = {}", s.mode(), clock.instant());
            }
            availability.markSuccess();
        } catch (RuntimeException e) {
            availability.markFailure(e);
            log.warn(
                    "Clock override state not loaded (Redis unavailable); using configured mode {}", properties.mode());
        }
    }

    private Instant targetOf(StoredState s) {
        return switch (ClockMode.valueOf(s.mode())) {
            case FIXED -> s.fixedInstant();
            case OFFSET -> clock.realInstant().plus(Duration.parse(s.offset()));
            case SYSTEM -> clock.realInstant();
        };
    }
}
