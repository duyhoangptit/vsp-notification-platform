package com.pnh.shared.config;

import com.pnh.shared.domain.NotificationIdGenerator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class IdConfig {

    @Bean
    NotificationIdGenerator notificationIdGenerator(Clock clock) {
        return new NotificationIdGenerator(clock);
    }
}
