package com.pnh.shared.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.pnh.provider.application.ProviderResilienceFactory;
import com.pnh.shared.config.PnhMetrics;
import com.pnh.support.IntegrationTestBase;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** T027 */
class ObservabilityMetricsIT extends IntegrationTestBase {

    @Autowired
    ProviderResilienceFactory resilience;

    @Autowired
    PnhMetrics metrics;

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void exposesProviderAndNotificationMetrics() throws Exception {
        var r = resilience.forProvider("SMS", "METRICS_PROBE");
        metrics.notificationStatus("EMAIL", "SENT");
        metrics.dispatchLatency("OTP", Duration.ofMillis(120));
        String body = scrape();
        assertThat(body).contains("resilience4j_circuitbreaker_state{").contains("name=\"METRICS_PROBE\"");
        assertThat(body).contains("resilience4j_retry_calls");
        assertThat(body)
                .contains("pnh_notifications_total{")
                .contains("channel=\"EMAIL\"")
                .contains("status=\"SENT\"");
        assertThat(body).contains("pnh_dispatch_latency_seconds").contains("category=\"OTP\"");
        assertThat(body).contains("pnh_redis_fallback_active");

        r.circuitBreaker().transitionToOpenState();
        String after = scrape();
        assertThat(after.lines()
                        .filter(l -> l.startsWith("resilience4j_circuitbreaker_state{")
                                && l.contains("name=\"METRICS_PROBE\"")
                                && l.contains("state=\"open\"")))
                .anySatisfy(l -> assertThat(l).endsWith(" 1.0"));
        resilience.reset("SMS", "METRICS_PROBE");
    }
}
