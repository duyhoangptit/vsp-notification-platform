package com.pnh.template.adapter.in.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** console-api Template. */
public record TemplateView(
        String templateCode,
        String templateName,
        String channelType,
        String category,
        boolean isCritical,
        String otpVariable,
        String expiryVariable,
        String subjectTemplate,
        String titleTemplate,
        String bodyTemplate,
        Map<String, Object> externalTemplateRef,
        String status,
        List<String> requiredVariables,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy,
        long version) {}
