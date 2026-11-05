package com.pnh.provider.application;

import com.pnh.provider.ChannelRegistry;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.infra.CacheInvalidationBus;
import com.pnh.shared.infra.CacheNames;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Hot reload (T170, R13): when a channel's config changes on ANY replica (cache-invalidation broadcast), the circuit
 * breakers of that channel's providers are reset. Adapters key their SMTP / HTTP / OAuth clients by
 * {@code configId:version}, so the next delivery builds a fresh client from the new settings.
 */
@Component
class ProviderClientCache implements InitializingBean {

    private final CacheInvalidationBus bus;
    private final ChannelRegistry registry;
    private final ProviderResilienceFactory resilience;

    ProviderClientCache(CacheInvalidationBus bus, ChannelRegistry registry, ProviderResilienceFactory resilience) {
        this.bus = bus;
        this.registry = registry;
        this.resilience = resilience;
    }

    @Override
    public void afterPropertiesSet() {
        bus.addListener(inv -> {
            if (!CacheNames.CHANNEL_CONFIGS.equals(inv.cacheName())) {
                return;
            }
            ChannelType channel = ChannelType.parseOrNull(inv.key());
            if (channel == null) {
                return;
            }
            for (String key : registry.providerKeys(channel)) {
                resilience.reset(channel.value(), key);
            }
        });
    }
}
