package com.pnh.provider.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.provider.PermanentProviderException;
import com.pnh.provider.TransientProviderException;
import com.pnh.provider.application.ProviderResilience;
import com.pnh.provider.application.ProviderResilienceFactory;
import com.pnh.provider.application.ResilienceProperties;
import com.pnh.shared.domain.ErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ProviderResilienceFactoryTest {

    private final List<Object> events = new ArrayList<>();
    private final ApplicationEventPublisher publisher = events::add;
    private final ProviderResilienceFactory factory =
            new ProviderResilienceFactory(ResilienceProperties.defaults(), publisher, java.time.Clock.systemUTC());

    @Test
    void retryPolicy() {
        ProviderResilience r = factory.forProvider("SMS", "GENERIC_HTTP_SMS");
        RetryConfig cfg = r.retry().getRetryConfig();
        assertThat(cfg.getMaxAttempts()).isEqualTo(4);
        long first = cfg.getIntervalBiFunction().apply(1, null);
        long second = cfg.getIntervalBiFunction().apply(2, null);
        long third = cfg.getIntervalBiFunction().apply(3, null);
        assertThat(first).isBetween(800L, 1200L);
        assertThat(second).isBetween(1600L, 2400L);
        assertThat(third).isBetween(3200L, 4800L);
        assertThat(cfg.getExceptionPredicate().test(new TransientProviderException("503")))
                .isTrue();
        assertThat(cfg.getExceptionPredicate().test(new PermanentProviderException(ErrorCode.INVALID_RECIPIENT, "bad")))
                .isFalse();
        assertThat(cfg.getExceptionPredicate().test(new IllegalStateException()))
                .isFalse();
    }

    @Test
    void circuitBreakerPolicy() {
        CircuitBreakerConfig cfg =
                factory.forProvider("SMS", "GENERIC_HTTP_SMS").circuitBreaker().getCircuitBreakerConfig();
        assertThat(cfg.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);
        assertThat(cfg.getSlidingWindowSize()).isEqualTo(10);
        assertThat(cfg.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(cfg.getMinimumNumberOfCalls()).isEqualTo(20);
        assertThat(cfg.getWaitIntervalFunctionInOpenState().apply(1))
                .isEqualTo(Duration.ofSeconds(30).toMillis());
        assertThat(cfg.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
        assertThat(cfg.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isTrue();
        // permanent errors (bad recipient) are not provider failures
        assertThat(cfg.getRecordExceptionPredicate().test(new TransientProviderException("x")))
                .isTrue();
        assertThat(cfg.getRecordExceptionPredicate()
                        .test(new PermanentProviderException(ErrorCode.INVALID_RECIPIENT, "x")))
                .isFalse();
    }

    @Test
    void timeLimiterFiveSeconds() {
        assertThat(factory.forProvider("SMS", "GENERIC_HTTP_SMS")
                        .timeLimiter()
                        .getTimeLimiterConfig()
                        .getTimeoutDuration())
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void instancesCachedPerProviderKeyAndResettable() {
        ProviderResilience a = factory.forProvider("SMS", "GENERIC_HTTP_SMS");
        assertThat(factory.forProvider("SMS", "GENERIC_HTTP_SMS")).isSameAs(a);
        assertThat(factory.forProvider("EMAIL", "SMTP")).isNotSameAs(a);
        a.circuitBreaker().transitionToOpenState();
        factory.reset("SMS", "GENERIC_HTTP_SMS");
        assertThat(factory.forProvider("SMS", "GENERIC_HTTP_SMS")
                        .circuitBreaker()
                        .getState()
                        .name())
                .isEqualTo("CLOSED");
    }

    @Test
    void circuitStateTransitionsArePublished() {
        ProviderResilience r = factory.forProvider("PUSH", "FCM");
        r.circuitBreaker().transitionToOpenState();
        assertThat(events)
                .anySatisfy(e -> assertThat(e)
                        .isInstanceOfSatisfying(com.pnh.provider.ProviderAvailabilityChanged.class, p -> {
                            assertThat(p.providerKey()).isEqualTo("FCM");
                            assertThat(p.channelType()).isEqualTo("PUSH");
                            assertThat(p.state()).isEqualTo("OPEN");
                        }));
    }

    @Test
    void openCircuitRejectionIsNotRetried() {
        RetryConfig cfg = factory.forProvider("SMS", "X").retry().getRetryConfig();
        CallNotPermittedException ex = CallNotPermittedException.createCallNotPermittedException(
                factory.forProvider("SMS", "X").circuitBreaker());
        assertThat(cfg.getExceptionPredicate().test(ex)).isFalse();
    }
}
