package com.pnh.provider.application;

import com.pnh.shared.domain.Category;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;

/** Resilience components of one provider instance. OTP traffic has its own bulkhead (research R6). */
public record ProviderResilience(
        Retry retry,
        CircuitBreaker circuitBreaker,
        TimeLimiter timeLimiter,
        Bulkhead standardBulkhead,
        Bulkhead otpBulkhead) {

    public Bulkhead bulkhead(Category category) {
        return category == Category.OTP ? otpBulkhead : standardBulkhead;
    }
}
