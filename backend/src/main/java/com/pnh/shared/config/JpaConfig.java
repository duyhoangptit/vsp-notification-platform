package com.pnh.shared.config;

import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** JPA auditing with timestamps from the Clock bean, never the JVM clock (R29). */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "pnhAuditorAware", dateTimeProviderRef = "pnhDateTimeProvider")
public class JpaConfig {

    @Bean
    DateTimeProvider pnhDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
