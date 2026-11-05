package com.pnh.provider.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code pnh.resilience.*} — per-provider Resilience4j policy (research R7). */
@ConfigurationProperties("pnh.resilience")
public record ResilienceProperties(
        Integer maxAttempts,
        Duration retryInitialInterval,
        Double retryMultiplier,
        Double retryJitter,
        Duration callTimeout,
        Integer cbWindowSeconds,
        Float cbFailureRateThreshold,
        Integer cbMinimumCalls,
        Duration cbWaitInOpen,
        Integer cbHalfOpenCalls,
        Integer bulkheadStandard,
        Integer bulkheadOtp) {

    public ResilienceProperties {
        maxAttempts = maxAttempts == null ? 4 : maxAttempts;
        retryInitialInterval = retryInitialInterval == null ? Duration.ofSeconds(1) : retryInitialInterval;
        retryMultiplier = retryMultiplier == null ? 2.0 : retryMultiplier;
        retryJitter = retryJitter == null ? 0.2 : retryJitter;
        callTimeout = callTimeout == null ? Duration.ofSeconds(5) : callTimeout;
        cbWindowSeconds = cbWindowSeconds == null ? 10 : cbWindowSeconds;
        cbFailureRateThreshold = cbFailureRateThreshold == null ? 50f : cbFailureRateThreshold;
        cbMinimumCalls = cbMinimumCalls == null ? 20 : cbMinimumCalls;
        cbWaitInOpen = cbWaitInOpen == null ? Duration.ofSeconds(30) : cbWaitInOpen;
        cbHalfOpenCalls = cbHalfOpenCalls == null ? 5 : cbHalfOpenCalls;
        bulkheadStandard = bulkheadStandard == null ? 40 : bulkheadStandard;
        bulkheadOtp = bulkheadOtp == null ? 20 : bulkheadOtp;
    }

    public static ResilienceProperties defaults() {
        return new ResilienceProperties(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Upper bound of one delivery including retries (used to validate the stuck timeout, R23). */
    public Duration maxAttemptDuration() {
        Duration total = callTimeout.multipliedBy(maxAttempts);
        Duration backoff = Duration.ZERO;
        Duration step = retryInitialInterval;
        for (int i = 1; i < maxAttempts; i++) {
            backoff = backoff.plus(step);
            step = Duration.ofMillis((long) (step.toMillis() * retryMultiplier));
        }
        return total.plus(backoff);
    }
}
