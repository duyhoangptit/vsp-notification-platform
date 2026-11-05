package com.pnh.alerting.domain;

import com.pnh.shared.audit.AuditableConfigEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Incident rule settings (seeded; console edits values only). */
@Entity
@Table(name = "pnh_alert_rules")
public class AlertRule extends AuditableConfigEntity {

    @Id
    @Column(name = "rule_code")
    private String ruleCode;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private BigDecimal threshold;

    @Column(name = "window_minutes", nullable = false)
    private int windowMinutes;

    @Column(name = "min_sample")
    private Integer minSample;

    @Column(name = "cooldown_minutes", nullable = false)
    private int cooldownMinutes;

    protected AlertRule() {}

    public String getRuleCode() {
        return ruleCode;
    }

    public AlertRuleCode code() {
        return AlertRuleCode.valueOf(ruleCode);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public Integer getMinSample() {
        return minSample;
    }

    public int getCooldownMinutes() {
        return cooldownMinutes;
    }

    public void update(
            boolean enabled, BigDecimal threshold, int windowMinutes, Integer minSample, int cooldownMinutes) {
        if (threshold == null || threshold.signum() < 0) {
            throw new IllegalArgumentException("threshold must be >= 0");
        }
        if (windowMinutes < 1 || windowMinutes > 1440) {
            throw new IllegalArgumentException("window_minutes must be 1..1440");
        }
        if (cooldownMinutes < 5 || cooldownMinutes > 1440) {
            throw new IllegalArgumentException("cooldown_minutes must be 5..1440");
        }
        if (minSample != null && minSample < 1) {
            throw new IllegalArgumentException("min_sample must be >= 1");
        }
        this.enabled = enabled;
        this.threshold = threshold;
        this.windowMinutes = windowMinutes;
        this.minSample = minSample;
        this.cooldownMinutes = cooldownMinutes;
    }
}
