package com.pnh.provider.sms.web;

import com.pnh.provider.ProviderSettings;
import com.pnh.provider.application.ChannelConfigProvider;
import com.pnh.provider.sms.HmacCallbackVerifier;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.tracking.DeliveryReport;
import com.pnh.tracking.TrackingApi;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generic SMS delivery reports (provider-callbacks.openapi.yaml): {@code POST /api/v1/callbacks/sms/{provider_key}},
 * verified with {@code X-PNH-Signature}/{@code X-PNH-Timestamp} and the provider's {@code callback_hmac_secret}
 * BEFORE any state change (constitution IV).
 */
@RestController
class SmsCallbackController {

    private final ChannelConfigProvider configs;
    private final HmacCallbackVerifier verifier;
    private final TrackingApi tracking;
    private final JsonMapper json;

    SmsCallbackController(
            ChannelConfigProvider configs, HmacCallbackVerifier verifier, TrackingApi tracking, JsonMapper json) {
        this.configs = configs;
        this.verifier = verifier;
        this.tracking = tracking;
        this.json = json;
    }

    @PostMapping("/api/v1/callbacks/sms/{providerKey}")
    ResponseEntity<Void> report(
            @PathVariable String providerKey,
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-PNH-Signature", required = false) String signature,
            @RequestHeader(value = "X-PNH-Timestamp", required = false) String timestamp) {
        Optional<ProviderSettings> cfg = configs.settingsFor(ChannelType.SMS, providerKey);
        if (cfg.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body;
        try {
            body = json.readValue(rawBody, new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
        String messageId = str(body.get("message_id"));
        String requestId = str(body.get("request_id"));
        if (!verifier.verifyGeneric(cfg.get().secret("callback_hmac_secret"), timestamp, signature, rawBody)) {
            tracking.recordIgnoredCallback(providerKey, requestId, messageId, "invalid signature");
            return ResponseEntity.status(401).build();
        }
        String status = str(body.get("status"));
        DeliveryReport.Outcome outcome =
                "DELIVERED".equals(status) ? DeliveryReport.Outcome.DELIVERED : DeliveryReport.Outcome.FAILED;
        ErrorCode code = "DELIVERED".equals(status)
                ? null
                : "EXPIRED".equals(status) || "REJECTED".equals(status) || "FAILED".equals(status)
                        ? ErrorCode.PROVIDER_REJECTED
                        : null;
        String sanitized = new String(rawBody, StandardCharsets.UTF_8);
        tracking.applyDeliveryReport(new DeliveryReport(
                providerKey,
                requestId,
                messageId,
                outcome,
                code,
                str(body.get("error_message")),
                sanitized.length() > 4000 ? null : sanitized));
        return ResponseEntity.noContent().build();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
