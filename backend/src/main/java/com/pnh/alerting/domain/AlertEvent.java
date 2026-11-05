package com.pnh.alerting.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Append-only alert history row (pnh_alert_events) — also the durable cooldown source (R24). */
public record AlertEvent(
        Long id,
        String ruleCode,
        String channelType,
        Kind kind,
        BigDecimal measuredValue,
        BigDecimal threshold,
        Integer windowMinutes,
        Instant occurredAt,
        List<String> recipients,
        SendStatus sendStatus,
        String sendError,
        Map<String, Object> details) {

    public enum Kind {
        FIRED,
        RESOLVED
    }

    public enum SendStatus {
        SENT,
        SEND_FAILED,
        NO_RECIPIENTS
    }
}
