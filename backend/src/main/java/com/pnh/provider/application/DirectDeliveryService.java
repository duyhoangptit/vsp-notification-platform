package com.pnh.provider.application;

import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ChannelRegistry;
import com.pnh.provider.DeliveryRequest;
import com.pnh.provider.DeliveryResult;
import com.pnh.provider.DirectDeliveryApi;
import com.pnh.provider.ProviderSettings;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
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
