package com.pnh.coreapi.application;

import com.pnh.coreapi.ClientPrincipal;
import com.pnh.coreapi.adapter.in.web.dto.DirectContent;
import com.pnh.coreapi.adapter.in.web.dto.SendRequest;
import com.pnh.coreapi.domain.RecipientValidator;
import com.pnh.provider.ChannelCapabilities;
import com.pnh.provider.ChannelRegistry;
import com.pnh.shared.domain.Category;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ContentMode;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.HtmlSanitizerPolicy;
import com.pnh.shared.web.PnhException;
import com.pnh.template.RenderedContent;
import com.pnh.template.TemplateApi;
import com.pnh.template.TemplateInfo;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Intake validation (T087, T114): channel, content mode, per-channel required fields and limits, recipient, template
 * status/channel/variables, OTP expiry derivation (FR-029, R27); renders template content NOW (R8 / fix I1) and
 * sanitizes direct EMAIL HTML (R28).
 */
@Component
public class IntakeValidator {

    static final int EMAIL_MAX_BODY_BYTES = 1_048_576;
    static final int PUSH_MAX_PAYLOAD_BYTES = 4096;
    static final int MAX_OTP_MINUTES = 1440;

    private final ChannelRegistry registry;
    private final TemplateApi templates;
    private final Clock clock;

    public IntakeValidator(ChannelRegistry registry, TemplateApi templates, Clock clock) {
        this.registry = registry;
        this.templates = templates;
        this.clock = clock;
    }

    public ValidatedNotification validate(SendRequest req, ClientPrincipal client) {
        ChannelType channel = ChannelType.parseOrNull(req.channelType());
        if (channel == null || !registry.isRegistered(channel)) {
            throw new PnhException(ErrorCode.UNSUPPORTED_CHANNEL, "Channel " + req.channelType() + " is not supported");
        }
        boolean templateMode = req.templateCode() != null && !req.templateCode().isBlank();
        boolean directMode = req.content() != null;
        if (templateMode == directMode) {
            throw new PnhException(ErrorCode.CONTENT_MODE_CONFLICT, "Provide exactly one of template_code or content");
        }
        ChannelCapabilities caps = registry.capabilities(channel);
        String recipient = RecipientValidator.normalize(caps.recipientType(), req.recipientTarget());
        Instant now = clock.instant();
        return templateMode
                ? templateMode(req, client, channel, caps, recipient, now)
                : directMode(req, client, channel, caps, recipient, now);
    }

    private ValidatedNotification templateMode(
            SendRequest req,
            ClientPrincipal client,
            ChannelType channel,
            ChannelCapabilities caps,
            String recipient,
            Instant now) {
        TemplateInfo t = templates
                .find(req.templateCode())
                .orElseThrow(() -> new PnhException(
                        ErrorCode.TEMPLATE_NOT_FOUND, "Template " + req.templateCode() + " not found"));
        if (!t.active()) {
            throw new PnhException(ErrorCode.TEMPLATE_INACTIVE, "Template " + t.templateCode() + " is inactive");
        }
        if (!channel.value().equals(t.channelType())) {
            throw new PnhException(
                    ErrorCode.TEMPLATE_CHANNEL_MISMATCH,
                    "Template " + t.templateCode() + " is for channel " + t.channelType() + ", not " + channel);
        }
        Map<String, String> vars = req.variables() == null ? Map.of() : req.variables();
        List<String> missing = templates.missingVariables(t, vars);
        if (!missing.isEmpty()) {
            throw PnhException.missingVariables(missing);
        }
        Category category = Category.valueOf(t.category());
        String otpValue = null;
        Instant expiresAt = req.expiresAt();
        if (category == Category.OTP) {
            otpValue = t.otpVariable() == null ? null : vars.get(t.otpVariable());
            expiresAt = otpExpiry(req.expiresAt(), t.expiryVariable(), vars, now);
        } else if (expiresAt != null) {
            requireFuture(expiresAt, now);
        }
        RenderedContent r = templates.render(t, vars);
        checkLength(caps, channel, r.subject(), r.title(), r.body(), null);
        return new ValidatedNotification(
                client.clientId(),
                req.clientRequestId(),
                req.userId(),
                channel.value(),
                recipient,
                category,
                ContentMode.TEMPLATE,
                t.templateCode(),
                t.version(),
                r.subject(),
                r.title(),
                r.body(),
                null,
                expiresAt,
                otpValue,
                false);
    }

    private ValidatedNotification directMode(
            SendRequest req,
            ClientPrincipal client,
            ChannelType channel,
            ChannelCapabilities caps,
            String recipient,
            Instant now) {
        if (!client.allowDirectContent() || !caps.supportsDirectContent() || caps.requiresExternalTemplate()) {
            throw new PnhException(
                    ErrorCode.DIRECT_CONTENT_NOT_ALLOWED, "Direct content is not allowed for this client/channel");
        }
        DirectContent c = req.content();
        if (c.body() == null || c.body().isBlank()) {
            throw PnhException.field(ErrorCode.MISSING_CONTENT_FIELD, "content.body", "content.body is required");
        }
        if (caps.requiresSubject() && (c.subject() == null || c.subject().isBlank())) {
            throw PnhException.field(
                    ErrorCode.MISSING_CONTENT_FIELD, "content.subject", "content.subject is required for " + channel);
        }
        if (caps.requiresTitle() && (c.title() == null || c.title().isBlank())) {
            throw PnhException.field(
                    ErrorCode.MISSING_CONTENT_FIELD, "content.title", "content.title is required for " + channel);
        }
        Category category = req.category() == null ? Category.STANDARD : Category.valueOf(req.category());
        String otpValue = null;
        Instant expiresAt = req.expiresAt();
        if (category == Category.OTP) {
            if (req.otpValue() == null || req.otpValue().isBlank()) {
                throw PnhException.field(
                        ErrorCode.OTP_VALUE_REQUIRED, "otp_value", "otp_value is required for direct OTP");
            }
            if (expiresAt == null) {
                throw PnhException.field(ErrorCode.OTP_EXPIRY_REQUIRED, "expires_at", "expires_at is required for OTP");
            }
            otpValue = req.otpValue();
        }
        if (expiresAt != null) {
            requireFuture(expiresAt, now);
        }
        String body = c.body();
        boolean sanitized = false;
        if (caps.supportsHtml()) {
            if (body.getBytes(StandardCharsets.UTF_8).length > EMAIL_MAX_BODY_BYTES) {
                throw new PnhException(ErrorCode.CONTENT_TOO_LONG, "Email body exceeds 1 MB");
            }
            String clean = HtmlSanitizerPolicy.sanitize(body);
            sanitized = !clean.equals(body);
            body = clean;
        }
        checkLength(caps, channel, c.subject(), c.title(), body, c.data());
        return new ValidatedNotification(
                client.clientId(),
                req.clientRequestId(),
                req.userId(),
                channel.value(),
                recipient,
                category,
                ContentMode.DIRECT,
                null,
                null,
                emptyToNull(c.subject()),
                emptyToNull(c.title()),
                body,
                c.data(),
                expiresAt,
                otpValue,
                sanitized);
    }

    /** FR-029 / R27: explicit future expires_at wins; else template expiry_variable (1..1440 minutes); else 422. */
    Instant otpExpiry(Instant requested, String expiryVariable, Map<String, String> vars, Instant now) {
        if (requested != null) {
            requireFuture(requested, now);
            return requested;
        }
        if (expiryVariable == null || vars.get(expiryVariable) == null) {
            throw PnhException.field(
                    ErrorCode.OTP_EXPIRY_REQUIRED, "expires_at", "OTP notifications require expires_at");
        }
        int minutes;
        try {
            minutes = Integer.parseInt(vars.get(expiryVariable).trim());
        } catch (NumberFormatException e) {
            minutes = -1;
        }
        if (minutes < 1 || minutes > MAX_OTP_MINUTES) {
            throw PnhException.field(
                    ErrorCode.VALIDATION_ERROR,
                    "variables." + expiryVariable,
                    expiryVariable + " must be an integer number of minutes between 1 and 1440");
        }
        return now.plus(Duration.ofMinutes(minutes));
    }

    private static void requireFuture(Instant expiresAt, Instant now) {
        if (!expiresAt.isAfter(now)) {
            throw PnhException.field(ErrorCode.VALIDATION_ERROR, "expires_at", "expires_at must be in the future");
        }
    }

    private static void checkLength(
            ChannelCapabilities caps,
            ChannelType channel,
            String subject,
            String title,
            String body,
            Map<String, String> data) {
        if (subject != null && subject.length() > 255) {
            throw new PnhException(ErrorCode.CONTENT_TOO_LONG, "Subject exceeds 255 characters");
        }
        if (title != null && title.length() > 255) {
            throw new PnhException(ErrorCode.CONTENT_TOO_LONG, "Title exceeds 255 characters");
        }
        if (caps.recipientType() == com.pnh.provider.RecipientType.DEVICE_TOKEN) {
            int bytes = utf8(title)
                    + utf8(body)
                    + (data == null
                            ? 0
                            : data.entrySet().stream()
                                    .mapToInt(e -> utf8(e.getKey()) + utf8(e.getValue()))
                                    .sum());
            if (bytes > Math.min(PUSH_MAX_PAYLOAD_BYTES, caps.maxContentLength())) {
                throw new PnhException(ErrorCode.CONTENT_TOO_LONG, "Push payload exceeds 4 KB");
            }
        } else if (!caps.supportsHtml() && body != null && body.length() > caps.maxContentLength()) {
            throw new PnhException(
                    ErrorCode.CONTENT_TOO_LONG,
                    channel + " content exceeds " + caps.maxContentLength() + " characters");
        }
    }

    private static int utf8(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
