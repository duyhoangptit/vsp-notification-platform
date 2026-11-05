package com.pnh.alerting.application;

import com.pnh.alerting.adapter.out.AlertEventRepository;
import com.pnh.alerting.adapter.out.AlertRuleRepository;
import com.pnh.alerting.domain.AlertEvent;
import com.pnh.alerting.domain.AlertRule;
import com.pnh.alerting.domain.AlertRuleCode;
import com.pnh.provider.ChannelRegistry;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.infra.DistributedLockService;
import com.pnh.tracking.ChannelOutcomeCounts;
import com.pnh.tracking.TrackingApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Incident evaluation (T152, R24), every 60 s on one instance: per rule and channel compare the measured value with the
 * threshold; FIRED when crossed and not in cooldown (durable state = latest pnh_alert_events row; SEND_FAILED is retried
 * on the next run), RESOLVED once when the condition clears. Windows use the Clock bean.
 */
@Service
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    record Measurement(AlertRuleCode rule, String channel, BigDecimal value, long samples, List<String> sampleIds) {}

    private final AlertRuleRepository rules;
    private final AlertEventRepository events;
    private final AlertSignalCollector signals;
    private final AlertMailer mailer;
    private final ChannelRegistry registry;
    private final TrackingApi tracking;
    private final DistributedLockService locks;
    private final Clock clock;
    private final String consoleBaseUrl;
    private final boolean jobsEnabled;

    public AlertEvaluator(
            AlertRuleRepository rules,
            AlertEventRepository events,
            AlertSignalCollector signals,
            AlertMailer mailer,
            ChannelRegistry registry,
            TrackingApi tracking,
            DistributedLockService locks,
            Clock clock,
            @Value("${pnh.console.base-url:http://localhost:5173}") String consoleBaseUrl,
            @Value("${pnh.jobs.enabled:true}") boolean jobsEnabled) {
        this.rules = rules;
        this.events = events;
        this.signals = signals;
        this.mailer = mailer;
        this.registry = registry;
        this.tracking = tracking;
        this.locks = locks;
        this.clock = clock;
        this.consoleBaseUrl = consoleBaseUrl;
        this.jobsEnabled = jobsEnabled;
    }

    @Scheduled(
            fixedDelayString = "${pnh.alerting.interval:60s}",
            initialDelayString = "${pnh.alerting.initial-delay:60s}")
    void scheduled() {
        if (jobsEnabled) {
            locks.tryRunExclusive("alert-evaluation", this::evaluateNow);
        }
    }

    public void evaluateNow() {
        Instant now = clock.instant();
        Map<String, AlertEvent> latest = new HashMap<>();
        for (AlertEvent e : events.latestPerRuleAndChannel()) {
            latest.put(e.ruleCode() + "|" + (e.channelType() == null ? "" : e.channelType()), e);
        }
        for (AlertRule rule : rules.findAllByOrderByRuleCodeAsc()) {
            if (!rule.isEnabled()) {
                continue;
            }
            Instant since = now.minus(Duration.ofMinutes(rule.getWindowMinutes()));
            Map<String, Measurement> measured = measure(rule, since, now);
            Set<String> channels = new TreeSet<>(measured.keySet());
            latest.values().stream()
                    .filter(e -> e.ruleCode().equals(rule.getRuleCode()) && e.kind() == AlertEvent.Kind.FIRED)
                    .forEach(e -> channels.add(e.channelType() == null ? "" : e.channelType()));
            for (String channel : channels) {
                Measurement m = measured.get(channel);
                AlertEvent last = latest.get(rule.getRuleCode() + "|" + channel);
                boolean active = last != null && last.kind() == AlertEvent.Kind.FIRED;
                boolean firing = m != null && crossed(rule, m, active);
                decide(rule, channel.isEmpty() ? null : channel, m, firing, last, now);
            }
        }
    }

    /**
     * {@code min_sample} only guards FIRING on small volumes: an active failure-rate incident clears when the rate
     * drops below the threshold (or no traffic is left), not merely because volume fell under the minimum (FR-044).
     */
    private boolean crossed(AlertRule rule, Measurement m, boolean active) {
        if (rule.code() == AlertRuleCode.CHANNEL_FAILURE_RATE
                && !active
                && rule.getMinSample() != null
                && m.samples() < rule.getMinSample()) {
            return false;
        }
        return m.value().compareTo(rule.getThreshold()) >= 0 && m.value().signum() > 0;
    }

    private void decide(AlertRule rule, String channel, Measurement m, boolean firing, AlertEvent last, Instant now) {
        if (firing) {
            boolean fire = last == null
                    || last.kind() == AlertEvent.Kind.RESOLVED
                    || last.sendStatus() == AlertEvent.SendStatus.SEND_FAILED
                    || last.occurredAt().isBefore(now.minus(Duration.ofMinutes(rule.getCooldownMinutes())));
            if (fire) {
                send(rule, channel, m, AlertEvent.Kind.FIRED, now);
            }
        } else if (last != null && last.kind() == AlertEvent.Kind.FIRED) {
            send(rule, channel, m, AlertEvent.Kind.RESOLVED, now);
        }
    }

    private void send(AlertRule rule, String channel, Measurement m, AlertEvent.Kind kind, Instant now) {
        BigDecimal value = m == null ? BigDecimal.ZERO : m.value();
        String ch = channel == null ? "ALL" : channel;
        String unit = switch (rule.code().unit()) {
            case "PERCENT" -> "%";
            case "MINUTES" -> " min";
            default -> "";
        };
        String subject = kind == AlertEvent.Kind.FIRED
                ? "[PNH][" + rule.getRuleCode() + "] " + ch + " – "
                        + value.stripTrailingZeros().toPlainString() + unit + " in " + rule.getWindowMinutes() + " min"
                : "[PNH][" + rule.getRuleCode() + "] " + ch + " – resolved";
        String link =
                consoleBaseUrl + "/notifications?status=FAILED" + (channel == null ? "" : "&channel_type=" + channel);
        List<String> sampleIds = m == null ? List.of() : m.sampleIds();
        StringBuilder body = new StringBuilder()
                .append(kind == AlertEvent.Kind.FIRED ? "Incident detected" : "Incident resolved")
                .append('\n')
                .append("Rule: ")
                .append(rule.getRuleCode())
                .append(" — ")
                .append(rule.code().description())
                .append('\n')
                .append("Channel: ")
                .append(ch)
                .append('\n')
                .append("Measured: ")
                .append(value.stripTrailingZeros().toPlainString())
                .append(unit)
                .append(" (threshold ")
                .append(rule.getThreshold().stripTrailingZeros().toPlainString())
                .append(unit)
                .append(", window ")
                .append(rule.getWindowMinutes())
                .append(" min)")
                .append('\n');
        if (!sampleIds.isEmpty()) {
            body.append("Sample notification ids: ")
                    .append(String.join(", ", sampleIds))
                    .append('\n');
        }
        body.append("Delivery Audit: ").append(link).append('\n');
        AlertMailer.Result result = mailer.send(subject, body.toString());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sample_notification_ids", sampleIds);
        details.put("audit_link", link);
        if (m != null) {
            details.put("samples", m.samples());
        }
        events.insert(new AlertEvent(
                null,
                rule.getRuleCode(),
                channel,
                kind,
                value.setScale(2, RoundingMode.HALF_UP),
                rule.getThreshold(),
                rule.getWindowMinutes(),
                now,
                result.recipients(),
                result.status(),
                result.error(),
                details));
        log.warn("Alert {} {} {} (send {})", kind, rule.getRuleCode(), ch, result.status());
    }

    private Map<String, Measurement> measure(AlertRule rule, Instant since, Instant now) {
        Map<String, Measurement> out = new HashMap<>();
        AlertRuleCode code = rule.code();
        switch (code) {
            case STUCK_DELIVERY_SPIKE -> {
                Map<String, ChannelOutcomeCounts> db = null;
                for (ChannelType c : registry.registeredChannels()) {
                    String key = AlertSignalCollector.key(code, c.value(), null);
                    Optional<Long> redisCount = signals.countRedis(key, since);
                    long count;
                    if (redisCount.isPresent()) {
                        count = redisCount.get();
                    } else {
                        // Redis unavailable: cluster-wide truth from Postgres, or this instance's window if larger
                        db = db == null ? dbCounts(since) : db;
                        ChannelOutcomeCounts dc = db.get(c.value());
                        count = Math.max(dc == null ? 0 : dc.stuck(), signals.countLocal(key, since));
                    }
                    if (count > 0) {
                        out.put(
                                c.value(),
                                new Measurement(
                                        code,
                                        c.value(),
                                        BigDecimal.valueOf(count),
                                        count,
                                        signals.samples(key, since, 5)));
                    }
                }
            }
            case CHANNEL_FAILURE_RATE -> {
                Map<String, ChannelOutcomeCounts> db = null;
                for (ChannelType c : registry.registeredChannels()) {
                    String okKey = AlertSignalCollector.key(code, c.value(), "ok");
                    String failKey = AlertSignalCollector.key(code, c.value(), "fail");
                    Optional<Long> ok = signals.countRedis(okKey, since);
                    Optional<Long> fail = signals.countRedis(failKey, since);
                    long okCount;
                    long failCount;
                    if (ok.isPresent() && fail.isPresent()) {
                        okCount = ok.get();
                        failCount = fail.get();
                    } else {
                        db = db == null ? dbCounts(since) : db;
                        ChannelOutcomeCounts dc = db.get(c.value());
                        long localOk = signals.countLocal(okKey, since);
                        long localFail = signals.countLocal(failKey, since);
                        boolean useDb = dc != null && dc.success() + dc.failure() >= localOk + localFail;
                        okCount = useDb ? dc.success() : localOk;
                        failCount = useDb ? dc.failure() : localFail;
                    }
                    long samples = okCount + failCount;
                    if (samples > 0) {
                        BigDecimal pct = BigDecimal.valueOf(failCount * 100)
                                .divide(BigDecimal.valueOf(samples), 2, RoundingMode.HALF_UP);
                        out.put(
                                c.value(),
                                new Measurement(code, c.value(), pct, samples, signals.samples(failKey, since, 5)));
                    }
                }
            }
            case INGEST_REJECTED_SPIKE -> {
                String key = AlertSignalCollector.key(code, null, null);
                long count = signals.countRedis(key, since).orElseGet(() -> signals.countLocal(key, since));
                if (count > 0) {
                    out.put("", new Measurement(code, null, BigDecimal.valueOf(count), count, List.of()));
                }
            }
            case PROVIDER_UNAVAILABLE -> {
                for (AlertSignalCollector.OpenCircuit oc : signals.openCircuits()) {
                    long minutes = Math.max(0, Duration.between(oc.since(), now).toMinutes());
                    BigDecimal v = BigDecimal.valueOf(minutes);
                    out.merge(
                            oc.channelType(),
                            new Measurement(code, oc.channelType(), v, 1, List.of(oc.providerKey())),
                            (a, b) -> a.value().compareTo(b.value()) >= 0 ? a : b);
                }
            }
        }
        return out;
    }

    private Map<String, ChannelOutcomeCounts> dbCounts(Instant since) {
        Map<String, ChannelOutcomeCounts> m = new HashMap<>();
        for (ChannelOutcomeCounts c : tracking.outcomeCounts(since)) {
            m.put(c.channelType(), c);
        }
        return m;
    }
}
