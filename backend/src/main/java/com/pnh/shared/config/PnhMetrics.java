package com.pnh.shared.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Metric names shared by modules (T055): {@code pnh_notifications_total}, {@code pnh_dispatch_latency_seconds}. */
@Component
public class PnhMetrics {

    private final MeterRegistry registry;

    public PnhMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void notificationStatus(String channel, String status) {
        Counter.builder("pnh.notifications")
                .description("Notifications entering a status, per channel")
                .tag("channel", channel == null ? "UNKNOWN" : channel)
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public void dispatchLatency(String category, Duration latency) {
        Timer.builder("pnh.dispatch.latency")
                .description("Accepted → SENDING latency per category")
                .tag("category", category)
                .publishPercentileHistogram()
                .register(registry)
                .record(latency);
    }

    public void providerAttempt(String providerKey, String outcome, Duration latency) {
        Timer.builder("pnh.provider.attempt")
                .tag("provider", providerKey)
                .tag("outcome", outcome)
                .register(registry)
                .record(latency);
    }

    public MeterRegistry registry() {
        return registry;
    }
}
