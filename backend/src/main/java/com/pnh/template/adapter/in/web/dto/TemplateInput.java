package com.pnh.template.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** console-api TemplateInput (+ version on update). */
public record TemplateInput(
        @NotBlank @Pattern(regexp = "^[A-Z0-9_]{3,100}$") String templateCode,
        @NotBlank @Size(max = 150) String templateName,
        @NotBlank @Size(max = 20) String channelType,
        @NotNull @Pattern(regexp = "STANDARD|OTP") String category,
        Boolean isCritical,
        @Size(max = 64) String otpVariable,
        @Size(max = 64) String expiryVariable,
        @Size(max = 255) String subjectTemplate,
        @Size(max = 255) String titleTemplate,
        @NotBlank String bodyTemplate,
        Map<String, Object> externalTemplateRef,
        Long version) {}
