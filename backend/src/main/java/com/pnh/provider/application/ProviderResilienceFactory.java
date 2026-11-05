package com.pnh.provider.application;

import com.pnh.provider.ProviderAvailabilityChanged;
import com.pnh.provider.TransientProviderException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Builds and caches Retry / CircuitBreaker / TimeLimiter / Bulkheads per provider key (research R7, T061). */
@Component
public class ProviderResilienceFactory {

    private final ResilienceProperties props;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.ofDefaults();
    private final RetryRegistry retries = RetryRegistry.ofDefaults();
    private final TimeLimiterRegistry timeLimiters = TimeLimiterRegistry.ofDefaults();
    private final BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    private final Map<String, ProviderResilience> instances = new ConcurrentHashMap<>();

    public ProviderResilienceFactory(ResilienceProperties props, ApplicationEventPublisher publisher, Clock clock) {
        this.props = props;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Autowired
    public ProviderResilienceFactory(
            ResilienceProperties props, ApplicationEventPublisher publisher, Clock clock, MeterRegistry meterRegistry) {
        this(props, publisher, clock);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers).bindTo(meterRegistry);
        TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(meterRegistry);
        TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(timeLimiters).bindTo(meterRegistry);
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(meterRegistry);
    }

    public ResilienceProperties properties() {
        return props;
    }

    public ProviderResilience forProvider(String channelType, String providerKey) {
        return instances.computeIfAbsent(providerKey, key -> build(channelType, key));
    }

    /** Called after a configuration change: fresh circuit (research R13, T170). */
    public void reset(String channelType, String providerKey) {
        ProviderResilience existing = instances.get(providerKey);
        if (existing != null) {
            existing.circuitBreaker().reset();
        }
    }

    private ProviderResilience build(String channelType, String providerKey) {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(props.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        props.retryInitialInterval(), props.retryMultiplier(), props.retryJitter()))
                .retryOnException(TransientProviderException.class::isInstance)
                .failAfterMaxAttempts(false)
                .build();
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(props.cbWindowSeconds())
                .failureRateThreshold(props.cbFailureRateThreshold())
                .minimumNumberOfCalls(props.cbMinimumCalls())
                .waitDurationInOpenState(props.cbWaitInOpen())
                .permittedNumberOfCallsInHalfOpenState(props.cbHalfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(TransientProviderException.class::isInstance)
                .build();
        TimeLimiterConfig tlConfig = TimeLimiterConfig.custom()
                .timeoutDuration(props.callTimeout())
                .cancelRunningFuture(true)
                .build();
        BulkheadConfig standard = BulkheadConfig.custom()
                .maxConcurrentCalls(props.bulkheadStandard())
                .maxWaitDuration(Duration.ofSeconds(30))
                .build();
        BulkheadConfig otp = BulkheadConfig.custom()
                .maxConcurrentCalls(props.bulkheadOtp())
                .maxWaitDuration(Duration.ofSeconds(10))
                .build();

        CircuitBreaker cb = circuitBreakers.circuitBreaker(providerKey, cbConfig);
        cb.getEventPublisher()
                .onStateTransition(e -> publisher.publishEvent(new ProviderAvailabilityChanged(
                        channelType,
                        providerKey,
                        e.getStateTransition().getToState().name(),
                        clock.instant())));
        Retry retry = retries.retry(providerKey, retryConfig);
        TimeLimiter tl = timeLimiters.timeLimiter(providerKey, tlConfig);
        Bulkhead bs = bulkheads.bulkhead(providerKey + ".standard", standard);
        Bulkhead bo = bulkheads.bulkhead(providerKey + ".otp", otp);
        return new ProviderResilience(retry, cb, tl, bs, bo);
    }
}
