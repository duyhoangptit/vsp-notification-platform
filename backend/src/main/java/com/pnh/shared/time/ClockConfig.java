package com.pnh.shared.time;

import java.time.Clock;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/** The ONLY place allowed to read the JVM clock (ArchUnit {@code ClockUsageArchTest}). */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    @Primary
    PnhClock clock(ClockProperties properties, Environment environment) {
        if (properties.overrideEnabled()
                && Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("pnh.clock.override-enabled must not be true with profile 'prod'");
        }
        PnhClock clock = new PnhClock(Clock.systemUTC());
        clock.apply(properties);
        return clock;
    }
}
