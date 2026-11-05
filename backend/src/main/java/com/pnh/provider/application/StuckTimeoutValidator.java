package com.pnh.provider.application;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Startup guard (R23): the stuck timeout must exceed the longest possible delivery (all attempts + backoff), otherwise
 * the sweeper could fail a notification whose provider call is still legitimately running.
 */
@Component
class StuckTimeoutValidator implements InitializingBean {

    private final ResilienceProperties resilience;
    private final Duration stuckTimeout;

    StuckTimeoutValidator(
            ResilienceProperties resilience, @Value("${pnh.delivery.stuck-timeout:5m}") Duration stuckTimeout) {
        this.resilience = resilience;
        this.stuckTimeout = stuckTimeout;
    }

    @Override
    public void afterPropertiesSet() {
        Duration max = resilience.maxAttemptDuration();
        if (stuckTimeout.compareTo(max) <= 0) {
            throw new IllegalStateException(
                    "pnh.delivery.stuck-timeout (" + stuckTimeout + ") must be greater than the maximum "
                            + "delivery duration " + max + " (attempts × call timeout + backoff)");
        }
    }
}
