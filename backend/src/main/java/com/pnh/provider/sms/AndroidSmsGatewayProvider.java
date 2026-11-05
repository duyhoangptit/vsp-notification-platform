package com.pnh.provider.sms;

import com.pnh.provider.ChannelCapabilities;
import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ConnectionTestResult;
import com.pnh.provider.DeliveryRequest;
import com.pnh.provider.DeliveryResult;
import com.pnh.provider.ProviderSettings;
import com.pnh.provider.RecipientType;
import com.pnh.shared.domain.ChannelType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * SMS Gateway for Android (research R14, verified 2026-11-04): {@code POST {base}/3rdparty/v1/messages} with Basic auth;
 * the hub's notification id is sent as the client-supplied message {@code id} so webhooks match even before SENT is
 * committed. Webhooks: {@code POST /api/v1/callbacks/sms/android-sms-gateway}.
 */
@Component
public class AndroidSmsGatewayProvider implements ChannelProvider {

    public static final String KEY = "ANDROID_SMS_GATEWAY";
    static final String CLOUD_BASE = "https://api.sms-gate.app";
    private final RestClient http = SmsSupport.restClient();

    record Outcome(int status, String body) {}

    @Override
    public ChannelType channelType() {
        return ChannelType.SMS;
    }

    @Override
    public String providerKey() {
        return KEY;
    }

    @Override
    public ChannelCapabilities capabilities() {
        return ChannelCapabilities.builder(RecipientType.PHONE)
                .maxContentLength(1600)
                .supportsDeliveryCallback(true)
                .build();
    }

    @Override
    public Set<String> secretKeys() {
        return Set.of("password", "webhook_signing_key");
    }

    @Override
    public void validateRecipient(String target) {
        SmsSupport.validateE164(target);
    }

    @Override
    public Map<String, String> validateSettings(Map<String, Object> settings, Map<String, String> secrets) {
        Map<String, String> errors = new LinkedHashMap<>();
        Object mode = settings.getOrDefault("mode", "cloud");
        if (!"cloud".equals(mode) && !"local".equals(mode)) {
            errors.put("settings.mode", "mode must be local or cloud");
        }
        Object base = settings.get("base_url");
        if ("local".equals(mode) && base == null) {
            errors.put("settings.base_url", "base_url is required in local mode");
        }
        if (base != null) {
            try {
                URI u = URI.create(String.valueOf(base));
                if (u.getHost() == null) {
                    errors.put("settings.base_url", "base_url must be an absolute http(s) URL");
                }
            } catch (IllegalArgumentException e) {
                errors.put("settings.base_url", "base_url must be an absolute http(s) URL");
            }
        }
        return errors;
    }

    @Override
    public DeliveryResult send(DeliveryRequest request) {
        ProviderSettings cfg = request.config();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", request.notificationId().toString());
        body.put("textMessage", Map.of("text", request.body()));
        body.put("phoneNumbers", List.of(request.recipient()));
        body.put("withDeliveryReport", true);
        try {
            Outcome out = http.post()
                    .uri(base(cfg.settings()) + "/3rdparty/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(
                            "Authorization",
                            basic(
                                    cfg.secret("username") != null ? cfg.secret("username") : cfg.setting("username"),
                                    cfg.secret("password")))
                    .body(body)
                    .exchange((req, res) -> new Outcome(res.getStatusCode().value(), SmsSupport.bodyOf(res)));
            if (out.status() < 200 || out.status() >= 300) {
                throw SmsSupport.classify(out.status(), out.body());
            }
            String id = SmsSupport.jsonField(out.body(), "id");
            return new DeliveryResult(id == null ? request.notificationId().toString() : id);
        } catch (ResourceAccessException e) {
            throw SmsSupport.io(e);
        }
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, Object> settings, Map<String, String> secrets) {
        try {
            String user = secrets.get("username") != null
                    ? secrets.get("username")
                    : String.valueOf(settings.get("username"));
            int status = http.get()
                    .uri(base(settings) + "/3rdparty/v1/devices")
                    .header("Authorization", basic(user, secrets.get("password")))
                    .exchange((req, res) -> res.getStatusCode().value());
            return status >= 200 && status < 300
                    ? ConnectionTestResult.ok()
                    : ConnectionTestResult.failed("Gateway answered HTTP " + status);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String base(Map<String, Object> settings) {
        Object b = settings.get("base_url");
        String base = b == null ? CLOUD_BASE : String.valueOf(b);
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String basic(String user, String password) {
        return "Basic "
                + Base64.getEncoder()
                        .encodeToString(((user == null ? "" : user) + ":" + (password == null ? "" : password))
                                .getBytes(StandardCharsets.UTF_8));
    }
}
