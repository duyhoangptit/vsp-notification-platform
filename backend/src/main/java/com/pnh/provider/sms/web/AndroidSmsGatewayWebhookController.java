package com.pnh.provider.sms.web;

import com.pnh.provider.ProviderSettings;
import com.pnh.provider.application.ChannelConfigProvider;
import com.pnh.provider.sms.AndroidSmsGatewayProvider;
import com.pnh.provider.sms.HmacCallbackVerifier;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.tracking.DeliveryReport;
import com.pnh.tracking.TrackingApi;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * SMS Gateway for Android webhooks: {@code X-Signature} = hex HMAC-SHA256(webhook_signing_key, raw body + X-Timestamp).
 * sms:delivered → DELIVERED, sms:failed → FAILED PROVIDER_REJECTED, sms:sent → timeline only.
 */
@RestController
class AndroidSmsGatewayWebhookController {

    private final ChannelConfigProvider configs;
    private final HmacCallbackVerifier verifier;
    private final TrackingApi tracking;
    private final JsonMapper json;

    AndroidSmsGatewayWebhookController(
            ChannelConfigProvider configs, HmacCallbackVerifier verifier, TrackingApi tracking, JsonMapper json) {
        this.configs = configs;
        this.verifier = verifier;
        this.tracking = tracking;
        this.json = json;
    }

    @PostMapping("/api/v1/callbacks/sms/android-sms-gateway")
    ResponseEntity<Void> webhook(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp) {
        Optional<ProviderSettings> cfg = configs.settingsFor(ChannelType.SMS, AndroidSmsGatewayProvider.KEY);
        if (cfg.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        Map<String, Object> body;
        try {
            body = json.readValue(rawBody, new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
        Object payloadObj = body.get("payload");
        Map<?, ?> payload = payloadObj instanceof Map<?, ?> m ? m : Map.of();
        String messageId = payload.get("messageId") == null ? null : String.valueOf(payload.get("messageId"));
        if (!verifier.verifyAndroidGateway(cfg.get().secret("webhook_signing_key"), timestamp, signature, rawBody)) {
            tracking.recordIgnoredCallback(AndroidSmsGatewayProvider.KEY, messageId, messageId, "invalid signature");
            return ResponseEntity.status(401).build();
        }
        String event = String.valueOf(body.get("event"));
        DeliveryReport.Outcome outcome = switch (event) {
            case "sms:delivered" -> DeliveryReport.Outcome.DELIVERED;
            case "sms:failed" -> DeliveryReport.Outcome.FAILED;
            case "sms:sent" -> DeliveryReport.Outcome.INFO;
            default -> null;
        };
        if (outcome != null && messageId != null) {
            String reason = payload.get("reason") == null ? null : String.valueOf(payload.get("reason"));
            tracking.applyDeliveryReport(new DeliveryReport(
                    AndroidSmsGatewayProvider.KEY,
                    messageId,
                    messageId,
                    outcome,
                    outcome == DeliveryReport.Outcome.FAILED ? ErrorCode.PROVIDER_REJECTED : null,
                    reason,
                    new String(rawBody, StandardCharsets.UTF_8)));
        }
        return ResponseEntity.noContent().build();
    }
}
