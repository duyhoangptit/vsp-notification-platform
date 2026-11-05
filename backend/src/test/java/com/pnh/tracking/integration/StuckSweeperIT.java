package com.pnh.tracking.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pnh.PnhApplication;
import com.pnh.shared.domain.Category;
import com.pnh.shared.domain.ContentMode;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationIdGenerator;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.shared.infra.PartitionService;
import com.pnh.support.ProviderStubs;
import com.pnh.support.ResultMessages;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestContainers;
import com.pnh.tracking.NewNotification;
import com.pnh.tracking.NotificationView;
import com.pnh.tracking.StatusChange;
import com.pnh.tracking.application.StuckNotificationSweeper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** T145 — stuck notifications fail, never resend (FR-040, R23). Time advanced with PnhClock, no sleeps. */
class StuckSweeperIT extends StubbedProvidersTestBase {

    static final Instant T0 = Instant.parse("2034-01-10T00:00:00Z");

    @Autowired
    StuckNotificationSweeper sweeper;

    @Autowired
    NotificationIdGenerator ids;

    @Autowired
    PartitionService partitions;

    @org.junit.jupiter.api.BeforeEach
    void partitionsFor2034() {
        partitions.ensureMonths(T0, 1);
    }

    private UUID create(NotificationStatus target, Instant expiresAt) {
        UUID id = ids.next();
        tracking.createReceived(new NewNotification(
                id,
                "sweep-client",
                "sw-" + id,
                "u",
                "+84901234567",
                "SMS",
                expiresAt == null ? Category.STANDARD : Category.OTP,
                ContentMode.DIRECT,
                null,
                null,
                null,
                null,
                "x",
                null,
                null,
                expiresAt));
        if (target != NotificationStatus.RECEIVED) {
            tracking.transition(StatusChange.of(id, NotificationStatus.RECEIVED, NotificationStatus.PROCESSED)
                    .build());
        }
        if (target == NotificationStatus.SENDING) {
            tracking.transition(StatusChange.of(id, NotificationStatus.PROCESSED, NotificationStatus.SENDING)
                    .markSendingStarted()
                    .build());
        }
        return id;
    }

    private NotificationView view(UUID id) {
        return tracking.findById(id).orElseThrow();
    }

    @Test
    void sweepsByTimeoutAndExpiryWithoutResending() {
        clock.setFixed(T0);
        sweeper.sweepNow(); // clear leftovers of other tests before measuring
        UUID sending = create(NotificationStatus.SENDING, null);
        UUID received = create(NotificationStatus.RECEIVED, null);
        UUID processed = create(NotificationStatus.PROCESSED, null);
        UUID otp = create(NotificationStatus.RECEIVED, T0.plus(Duration.ofMinutes(1)));

        clock.setFixed(T0.plus(Duration.ofMinutes(2)));
        sweeper.sweepNow();
        assertThat(view(otp).status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(view(otp).errorCode()).isEqualTo(ErrorCode.OTP_EXPIRED);
        assertThat(view(sending).status()).isEqualTo(NotificationStatus.SENDING);

        clock.setFixed(T0.plus(Duration.ofMinutes(6)));
        sweeper.sweepNow();
        assertThat(view(sending).status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(view(sending).errorCode()).isEqualTo(ErrorCode.DELIVERY_STATE_UNKNOWN);
        assertThat(view(received).status()).isEqualTo(NotificationStatus.RECEIVED);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pnh_notification_events WHERE notification_id=? AND event_type='SWEPT'",
                        Integer.class,
                        sending))
                .isEqualTo(1);

        clock.setFixed(T0.plus(Duration.ofMinutes(16)));
        sweeper.sweepNow();
        assertThat(view(received).errorCode()).isEqualTo(ErrorCode.PROCESSING_TIMEOUT);
        assertThat(view(processed).errorCode()).isEqualTo(ErrorCode.PROCESSING_TIMEOUT);

        ProviderStubs.WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/sms/send")));
        assertThat(ResultMessages.await(sending, 1, Duration.ofSeconds(20))
                        .get(0)
                        .value())
                .contains("\"status\":\"FAILED\"")
                .contains("DELIVERY_STATE_UNKNOWN");
    }

    @Test
    void processesAtMost500PerRun() {
        Instant t = T0.plus(Duration.ofDays(1));
        clock.setFixed(t);
        while (sweeper.sweepNow() > 0) {
            // drain stuck rows left by other tests sharing the database
        }
        List<UUID> stuck = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            stuck.add(create(NotificationStatus.SENDING, null));
        }
        clock.setFixed(t.plus(Duration.ofMinutes(6)));
        assertThat(sweeper.sweepNow()).isEqualTo(500);
        assertThat(sweeper.sweepNow()).isEqualTo(1);
        assertThat(stuck).allSatisfy(id -> assertThat(view(id).status()).isEqualTo(NotificationStatus.FAILED));
    }

    @Test
    void startupFailsWhenStuckTimeoutNotAboveMaxAttemptDuration() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(PnhApplication.class)
                        .profiles("test")
                        .run(
                                "--pnh.delivery.stuck-timeout=20s",
                                "--pnh.resilience.call-timeout=5s",
                                "--pnh.resilience.retry-initial-interval=1s",
                                "--spring.main.web-application-type=none",
                                "--spring.datasource.url=" + TestContainers.POSTGRES.getJdbcUrl(),
                                "--spring.datasource.username=pnh",
                                "--spring.datasource.password=pnh",
                                "--spring.flyway.enabled=false",
                                "--pnh.redis.address=" + TestContainers.redisAddress(),
                                "--spring.kafka.bootstrap-servers=" + TestContainers.KAFKA.getBootstrapServers(),
                                "--pnh.kafka.listeners-enabled=false"))
                .hasStackTraceContaining("stuck-timeout");
    }
}
