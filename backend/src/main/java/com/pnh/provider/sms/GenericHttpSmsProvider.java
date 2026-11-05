package com.pnh.provider.sms;

import com.pnh.provider.ChannelCapabilities;
import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ConnectionTestResult;
import com.pnh.provider.DeliveryRequest;
import com.pnh.provider.DeliveryResult;
import com.pnh.provider.PermanentProviderException;
import com.pnh.provider.RecipientType;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Hub-defined generic SMS gateway contract (research R14): {@code POST endpoint {to, message, sender, request_id}} with
 * {@code X-Api-Key}; returns {@code {message_id}}. Delivery reports arrive on {@code /api/v1/callbacks/sms/{provider_key}}.
 */
@Component
public class GenericHttpSmsProvider implements ChannelProvider {

    record HttpOutcome(int status, String body) {}

    public static final String KEY = "GENERIC_HTTP_SMS";
    private final RestClient http = SmsSupport.restClient();

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
        return Set.of("api_key", "api_secret", "callback_hmac_secret");
    }

    @Override
    public void validateRecipient(String target) {
        SmsSupport.validateE164(target);
    }

    @Override
    public Map<String, String> validateSettings(Map<String, Object> settings, Map<String, String> secrets) {
        Map<String, String> errors = new LinkedHashMap<>();
        Object endpoint = settings.get("endpoint");
        try {
            URI uri = URI.create(String.valueOf(endpoint));
            if (endpoint == null
                    || uri.getHost() == null
                    || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                errors.put("settings.endpoint", "endpoint must be an absolute http(s) URL");
            }
        } catch (IllegalArgumentException e) {
            errors.put("settings.endpoint", "endpoint must be an absolute http(s) URL");
        }
        if (settings.get("sender") == null
                || String.valueOf(settings.get("sender")).isBlank()) {
            errors.put("settings.sender", "sender is required");
        }
        return errors;
    }

    @Override
    public DeliveryResult send(DeliveryRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", request.recipient());
        body.put("message", request.body());
        body.put("sender", request.config().setting("sender", "PNH"));
        body.put("request_id", request.notificationId().toString());
        try {
            HttpOutcome out = http.post()
                    .uri(request.config().setting("endpoint"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        String key = request.config().secret("api_key");
                        if (key != null) {
                            h.set("X-Api-Key", key);
                        }
                    })
                    .body(body)
                    .exchange((req, response) ->
                            new HttpOutcome(response.getStatusCode().value(), SmsSupport.bodyOf(response)));
            if (out.status() < 200 || out.status() >= 300) {
                throw SmsSupport.classify(out.status(), out.body());
            }
            Object id = SmsSupport.jsonField(out.body(), "message_id");
            if (id == null) {
                throw new PermanentProviderException(ErrorCode.PROVIDER_REJECTED, "SMS gateway returned no message_id");
            }
            return new DeliveryResult(String.valueOf(id));
        } catch (ResourceAccessException e) {
            throw SmsSupport.io(e);
        }
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, Object> settings, Map<String, String> secrets) {
        try {
            int status = http.method(org.springframework.http.HttpMethod.OPTIONS)
                    .uri(String.valueOf(settings.get("endpoint")))
                    .headers(h -> {
                        if (secrets.get("api_key") != null) {
                            h.set("X-Api-Key", secrets.get("api_key"));
                        }
                    })
                    .exchange((req, res) -> res.getStatusCode().value());
            return status == 401 || status == 403 || status >= 500
                    ? ConnectionTestResult.failed("Gateway answered HTTP " + status)
                    : ConnectionTestResult.ok();
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
