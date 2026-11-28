package com.vsp.notification.provider.application;

import com.vsp.notification.provider.ChannelProvider;
import com.vsp.notification.provider.ChannelRegistry;
import com.vsp.notification.provider.DeliveryRequest;
import com.vsp.notification.provider.DeliveryResult;
import com.vsp.notification.provider.DirectDeliveryApi;
import com.vsp.notification.provider.ProviderSettings;
import com.vsp.notification.shared.domain.ChannelType;
import com.vsp.notification.shared.domain.ErrorCode;
import com.vsp.notification.shared.web.PnhException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class DirectDeliveryService implements DirectDeliveryApi {

    private final ChannelRegistry registry;
    private final ChannelConfigProvider configs;

    DirectDeliveryService(ChannelRegistry registry, ChannelConfigProvider configs) {
        this.registry = registry;
        this.configs = configs;
    }

    @Override
    public DeliveryResult send(ChannelType channel, String recipient, String subject, String body) {
        ChannelProvider provider = registry.activeProvider(channel);
        ProviderSettings settings =
                configs.active(channel).orElseThrow(() -> PnhException.of(ErrorCode.CHANNEL_NOT_CONFIGURED));
        return provider.send(
                new DeliveryRequest(UUID.randomUUID(), channel, recipient, subject, subject, body, null, settings));
    }
}
