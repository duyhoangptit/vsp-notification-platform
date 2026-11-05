package com.pnh.template;

import java.util.List;

/** Published view of a template (cached by code in {@code templateByCode}). */
public record TemplateInfo(
        String templateCode,
        String channelType,
        String category,
        boolean critical,
        String otpVariable,
        String expiryVariable,
        String subjectTemplate,
        String titleTemplate,
        String bodyTemplate,
        List<String> requiredVariables,
        boolean active,
        long version,
        String externalTemplateRef) {}
