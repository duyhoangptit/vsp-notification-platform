package com.pnh.provider;

import com.pnh.shared.domain.ChannelType;

/**
 * Sends a message straight through the channel's active provider, outside the notification pipeline (no preferences,
 * idempotency, rate limit or tracking) — used for incident alerts (research R24).
 */
public interface DirectDeliveryApi {
    DeliveryResult send(ChannelType channel, String recipient, String subject, String body);
}
