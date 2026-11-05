package com.pnh.alerting.application;

import com.pnh.alerting.adapter.out.AlertRecipientRepository;
import com.pnh.alerting.adapter.out.AlertRuleRepository;
import com.pnh.alerting.domain.AlertRecipient;
import com.pnh.alerting.domain.AlertRule;
import com.pnh.shared.audit.ConfigChanged;
import com.pnh.shared.audit.PnhAuditorAware;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.infra.CacheInvalidationBus;
import com.pnh.shared.infra.CacheNames;
import com.pnh.shared.web.PnhException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Alert recipients and rules management (T154): validation, optimistic locking, audit trail. */
@Service
public class AlertSettingsService {

    public record RecipientView(
            Long id,
            String email,
            String displayName,
            boolean isActive,
            Instant createdAt,
            String createdBy,
            Instant updatedAt,
            String updatedBy,
            long version) {}

    public record RuleView(
            String ruleCode,
            String description,
            boolean enabled,
            BigDecimal threshold,
            String thresholdUnit,
            int windowMinutes,
            Integer minSample,
            int cooldownMinutes,
            Instant createdAt,
            String createdBy,
            Instant updatedAt,
            String updatedBy,
            long version) {}

    private final AlertRecipientRepository recipients;
    private final AlertRuleRepository rules;
    private final ApplicationEventPublisher events;
    private final CacheInvalidationBus bus;
    private final Clock clock;

    public AlertSettingsService(
            AlertRecipientRepository recipients,
            AlertRuleRepository rules,
            ApplicationEventPublisher events,
            CacheInvalidationBus bus,
            Clock clock) {
        this.recipients = recipients;
        this.rules = rules;
        this.events = events;
        this.bus = bus;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RecipientView> recipients() {
        return recipients.findAllByOrderByEmailAsc().stream()
                .map(AlertSettingsService::view)
                .toList();
    }

    @Transactional
    public RecipientView addRecipient(String email, String displayName) {
        AlertRecipient r;
        try {
            r = new AlertRecipient(email, displayName);
        } catch (IllegalArgumentException e) {
            throw PnhException.field(ErrorCode.INVALID_FIELD, "email", "A valid email address is required");
        }
        if (recipients.findByEmail(r.getEmail()).isPresent()) {
            throw new PnhException(ErrorCode.RECIPIENT_EXISTS, "Recipient " + r.getEmail() + " already exists");
        }
        AlertRecipient saved = recipients.saveAndFlush(r);
        audit("ALERT_RECIPIENT", saved.getId().toString(), "CREATE", saved.getVersion(), Map.of(), snapshot(saved));
        return view(saved);
    }

    @Transactional
    public RecipientView updateRecipient(long id, String displayName, Boolean active, long version) {
        AlertRecipient r = recipient(id);
        if (r.isStale(version)) {
            throw PnhException.versionConflict(view(r));
        }
        Map<String, Object> before = snapshot(r);
        if (displayName != null) {
            r.setDisplayName(displayName);
        }
        if (active != null) {
            r.setActive(active);
        }
        AlertRecipient saved = recipients.saveAndFlush(r);
        audit(
                "ALERT_RECIPIENT",
                saved.getId().toString(),
                active != null && !active ? "DEACTIVATE" : "UPDATE",
                saved.getVersion(),
                before,
                snapshot(saved));
        return view(saved);
    }

    @Transactional
    public void removeRecipient(long id, long version) {
        AlertRecipient r = recipient(id);
        if (r.isStale(version)) {
            throw PnhException.versionConflict(view(r));
        }
        Map<String, Object> before = snapshot(r);
        recipients.delete(r);
        recipients.flush();
        audit("ALERT_RECIPIENT", Long.toString(id), "DELETE", version + 1, before, Map.of());
    }

    @Transactional(readOnly = true)
    public List<RuleView> rules() {
        return rules.findAllByOrderByRuleCodeAsc().stream()
                .map(AlertSettingsService::view)
                .toList();
    }

    @Transactional
    public RuleView updateRule(
            String code,
            boolean enabled,
            BigDecimal threshold,
            int windowMinutes,
            Integer minSample,
            int cooldownMinutes,
            long version) {
        AlertRule rule = rules.findById(code)
                .orElseThrow(() -> new PnhException(ErrorCode.NOT_FOUND, "Rule " + code + " not found"));
        if (rule.isStale(version)) {
            throw PnhException.versionConflict(view(rule));
        }
        Map<String, Object> before = snapshot(rule);
        try {
            rule.update(enabled, threshold, windowMinutes, minSample, cooldownMinutes);
        } catch (IllegalArgumentException e) {
            throw new PnhException(ErrorCode.INVALID_FIELD, e.getMessage());
        }
        AlertRule saved = rules.saveAndFlush(rule);
        audit("ALERT_RULE", code, "UPDATE", saved.getVersion(), before, snapshot(saved));
        return view(saved);
    }

    private AlertRecipient recipient(long id) {
        return recipients
                .findById(id)
                .orElseThrow(() -> new PnhException(ErrorCode.NOT_FOUND, "Recipient " + id + " not found"));
    }

    private void audit(
            String type,
            String id,
            String action,
            long version,
            Map<String, Object> before,
            Map<String, Object> after) {
        events.publishEvent(new ConfigChanged(
                type,
                id,
                action,
                version,
                before,
                after,
                Set.of(),
                Set.of(),
                PnhAuditorAware.currentActor(),
                clock.instant()));
        bus.clear(CacheNames.ALERT_SETTINGS);
    }

    private static Map<String, Object> snapshot(AlertRecipient r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", r.getEmail());
        m.put("display_name", r.getDisplayName());
        m.put("is_active", r.isActive());
        m.values().removeIf(Objects::isNull);
        return m;
    }

    private static Map<String, Object> snapshot(AlertRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", r.isEnabled());
        m.put("threshold", r.getThreshold().stripTrailingZeros().toPlainString());
        m.put("window_minutes", r.getWindowMinutes());
        m.put("min_sample", r.getMinSample());
        m.put("cooldown_minutes", r.getCooldownMinutes());
        m.values().removeIf(Objects::isNull);
        return m;
    }

    static RecipientView view(AlertRecipient r) {
        return new RecipientView(
                r.getId(),
                r.getEmail(),
                r.getDisplayName(),
                r.isActive(),
                r.getCreatedAt(),
                r.getCreatedBy(),
                r.getUpdatedAt(),
                r.getUpdatedBy(),
                r.getVersion());
    }

    static RuleView view(AlertRule r) {
        return new RuleView(
                r.getRuleCode(),
                r.code().description(),
                r.isEnabled(),
                r.getThreshold(),
                r.code().unit(),
                r.getWindowMinutes(),
                r.getMinSample(),
                r.getCooldownMinutes(),
                r.getCreatedAt(),
                r.getCreatedBy(),
                r.getUpdatedAt(),
                r.getUpdatedBy(),
                r.getVersion());
    }
}
