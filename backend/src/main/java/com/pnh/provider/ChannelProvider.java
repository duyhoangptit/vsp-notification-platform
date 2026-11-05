package com.pnh.provider;

import com.pnh.shared.domain.ChannelType;
import java.util.Map;

/** Provider port (constitution I, research R25). A new channel or vendor = a new bean implementing this. */
public interface ChannelProvider {

    ChannelType channelType();

    /** Unique per channel, e.g. SMTP, FCM, GENERIC_HTTP_SMS, ANDROID_SMS_GATEWAY. */
    String providerKey();

    ChannelCapabilities capabilities();

    /** Throws {@code PnhException(INVALID_RECIPIENT)} when the target is malformed for this channel. */
    void validateRecipient(String target);

    /** @throws TransientProviderException retryable failure; @throws PermanentProviderException final failure */
    DeliveryResult send(DeliveryRequest request);

    /** Non-sending connectivity/credential check (research R13). */
    ConnectionTestResult testConnection(Map<String, Object> settings, Map<String, String> secrets);

    /** Validates provider-specific settings; returns field errors keyed by setting name (empty = valid). */
    default Map<String, String> validateSettings(Map<String, Object> settings, Map<String, String> secrets) {
        return Map.of();
    }

    /** Names of secret keys this provider understands (write-only in the console). */
    default java.util.Set<String> secretKeys() {
        return java.util.Set.of();
    }
}
