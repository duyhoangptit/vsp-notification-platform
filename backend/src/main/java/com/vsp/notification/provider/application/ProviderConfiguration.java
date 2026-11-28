package com.vsp.notification.provider.application;

import com.vsp.notification.provider.ChannelProvider;
import com.vsp.notification.provider.ChannelRegistry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ProviderConfiguration {

    @Bean
    ChannelRegistry channelRegistry(List<ChannelProvider> providers, ChannelConfigProvider configs) {
        return new ChannelRegistry(providers, configs);
    }
}
