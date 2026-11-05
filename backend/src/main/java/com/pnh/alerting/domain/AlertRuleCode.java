package com.pnh.alerting.domain;

/** Incident rules (data-model, R24) with their threshold unit and description. */
public enum AlertRuleCode {
    STUCK_DELIVERY_SPIKE(
            "COUNT", "Notifications failed by the stuck sweeper (outcome unknown / not processed) in the window"),
    PROVIDER_UNAVAILABLE(
            "MINUTES", "A provider circuit has been open (provider unavailable) for at least the threshold"),
    CHANNEL_FAILURE_RATE(
            "PERCENT", "Share of FAILED among finished notifications of a channel in the window (min sample)"),
    INGEST_REJECTED_SPIKE("COUNT", "Rejected stream (Kafka) messages in the window");

    private final String unit;
    private final String description;

    AlertRuleCode(String unit, String description) {
        this.unit = unit;
        this.description = description;
    }

    public String unit() {
        return unit;
    }

    public String description() {
        return description;
    }
}
