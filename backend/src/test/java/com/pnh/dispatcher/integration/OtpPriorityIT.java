package com.pnh.dispatcher.integration;

import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pnh.provider.ChannelCapabilities;
import com.pnh.provider.ChannelConfigChanged;
import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ConnectionTestResult;
import com.pnh.provider.DeliveryRequest;
import com.pnh.provider.DeliveryResult;
import com.pnh.provider.RecipientType;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.MailSupport;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * T112 (fix C4) — deterministic lane isolation: STANDARD sends block on a latch in a test-only provider; every OTP
 * still reaches SENT (separate executor + bulkhead). No wall-clock latency assertions (SC-012: performance suite).
 */
@Import(OtpPriorityIT.BlockingProviderConfig.class)
@TestPropertySource(
        properties = {
            "pnh.resilience.call-timeout=120s",
            "pnh.resilience.cb-minimum-calls=100000",
            "pnh.delivery.stuck-timeout=15m", // must exceed 4 × 120 s + backoff (startup guard)
            // 2,000-message backlog on all executor threads: production pool size, not the small shared test pool
            "spring.datasource.hikari.maximum-pool-size=50"
        })
class OtpPriorityIT extends StubbedProvidersTestBase {

    static final CountDownLatch RELEASE = new CountDownLatch(1);

    /** Channel BLOCK: every send waits for the latch. */
    static class BlockingProvider implements ChannelProvider {
        @Override
        public ChannelType channelType() {
            return ChannelType.of("BLOCK");
        }

        @Override
        public String providerKey() {
            return "BLOCKING";
        }

        @Override
        public ChannelCapabilities capabilities() {
            return ChannelCapabilities.builder(RecipientType.PHONE).build();
        }

        @Override
        public void validateRecipient(String target) {}

        @Override
        public ConnectionTestResult testConnection(Map<String, Object> s, Map<String, String> x) {
            return ConnectionTestResult.ok();
        }

        @Override
        public DeliveryResult send(DeliveryRequest request) {
            try {
                RELEASE.await(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new DeliveryResult("blocked-" + request.notificationId());
        }
    }

    @TestConfiguration
    static class BlockingProviderConfig {
        @Bean
        BlockingProvider blockingProvider() {
            return new BlockingProvider();
        }
    }

    @Autowired
    org.springframework.context.ApplicationEventPublisher events;

    private int backlog(String clientId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pnh_notifications_history WHERE client_id=? AND category='STANDARD' "
                        + "AND status IN ('RECEIVED','PROCESSED','SENDING')",
                Integer.class,
                clientId);
    }

    @Test
    void otpOvertakesStandardBacklog() throws Exception {
        data.activeChannelConfig("BLOCK", "BLOCKING", Map.of(), Map.of());
        events.publishEvent(new ChannelConfigChanged("BLOCK", "BLOCKING", 0));
        ClientCreds c = data.client("SEND", 1_000_000, true, "ACTIVE");
        try {
            for (int i = 0; i < 2000; i++) {
                send(
                        mvc,
                        c,
                        json(
                                "client_request_id",
                                "std-" + i + "-" + UUID.randomUUID(),
                                "user_id",
                                "u" + (i % 50),
                                "channel_type",
                                "BLOCK",
                                "recipient_target",
                                "+84901234567",
                                "content",
                                "{\"body\":\"promo\"}"));
            }
            List<UUID> otps = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                otps.add(notificationId(send(
                        mvc,
                        c,
                        json(
                                "client_request_id",
                                "otp-" + i + "-" + UUID.randomUUID(),
                                "user_id",
                                "o" + i,
                                "channel_type",
                                "EMAIL",
                                "recipient_target",
                                MailSupport.uniqueAddress(),
                                "category",
                                "OTP",
                                "otp_value",
                                "1234" + (10 + i),
                                "expires_at",
                                "2099-01-01T00:00:00Z",
                                "content",
                                "{\"subject\":\"Code\",\"body\":\"<p>Code 1234" + (10 + i) + "</p>\"}"))));
            }
            for (UUID id : otps) {
                awaitStatus(tracking, id, NotificationStatus.SENT, Duration.ofSeconds(30));
            }
            assertThat(backlog(c.clientId())).isGreaterThanOrEqualTo(1900);
        } finally {
            RELEASE.countDown();
        }
        await().atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> backlog(c.clientId()) == 0);
    }
}
