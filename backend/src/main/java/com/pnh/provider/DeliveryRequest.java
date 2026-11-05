package com.pnh.provider;

import com.pnh.shared.domain.ChannelType;
import java.util.Map;
import java.util.UUID;

/** One provider call. {@code body} etc. are the real (unmasked) content for the provider only. */
public record DeliveryRequest(
        UUID notificationId,
        ChannelType channelType,
        String recipient,
        String subject,
        String title,
        String body,
        Map<String, String> data,
        ProviderSettings config) {

    @Override
    public String toString() {
        return "DeliveryRequest[notificationId=" + notificationId + ", channel=" + channelType + "]";
    }
}
