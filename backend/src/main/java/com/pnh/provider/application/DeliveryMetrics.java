package com.pnh.provider.application;

import com.pnh.shared.config.PnhMetrics;
import com.pnh.tracking.NotificationView;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Provider-side metrics (T097, T118): attempt latency and accepted → SENDING latency per category. */
@Component
public class DeliveryMetrics {

    private final PnhMetrics metrics;
    private final Clock clock;

    public DeliveryMetrics(PnhMetrics metrics, Clock clock) {
        this.metrics = metrics;
        this.clock = clock;
    }

    public void sendingStarted(NotificationView n) {
        Duration latency = Duration.between(n.createdAt(), clock.instant());
        metrics.dispatchLatency(n.category().name(), latency.isNegative() ? Duration.ZERO : latency);
    }

    public void attempt(String providerKey, String outcome, long startNanos) {
        metrics.providerAttempt(providerKey, outcome, Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
