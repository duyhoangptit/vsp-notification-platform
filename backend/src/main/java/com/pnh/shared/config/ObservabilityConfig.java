package com.pnh.shared.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Tracing helpers (T055). Spans {@code pnh.intake}, {@code pnh.dispatch}, {@code pnh.provider.send} are Micrometer
 * observations bridged to OpenTelemetry; each carries the {@code notification_id} attribute, so a span created for a
 * resubmitted event (new trace) is linked to the original by notification id. Never put OTPs or recipients in tags.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    @Component
    public static class Spans {
        private final ObservationRegistry registry;

        public Spans(ObservationRegistry registry) {
            this.registry = registry;
        }

        public <T> T observe(String name, String notificationId, Supplier<T> work) {
            Observation obs = Observation.createNotStarted(name, registry);
            if (notificationId != null) {
                obs.highCardinalityKeyValue("notification_id", notificationId);
            }
            return obs.observe(work);
        }

        public void observe(String name, String notificationId, Runnable work) {
            observe(name, notificationId, () -> {
                work.run();
                return null;
            });
        }
    }
}
