package com.pnh.alerting.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.alerting.application.AlertEvaluator;
import com.pnh.alerting.application.AlertSignalCollector;
import com.pnh.alerting.domain.AlertRuleCode;
import com.pnh.coreapi.IngestRejected;
import com.pnh.provider.ChannelConfigChanged;
import com.pnh.provider.ProviderAvailabilityChanged;
import com.pnh.shared.domain.Category;
import com.pnh.shared.domain.ContentMode;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationIdGenerator;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.shared.infra.PartitionService;
import com.pnh.support.MailSupport;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestContainers;
import com.pnh.tracking.NewNotification;
import com.pnh.tracking.NotificationOutcome;
import com.pnh.tracking.NotificationStuckFailed;
import com.pnh.tracking.StatusChange;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T146 — incident alerts (US11, R24). Each test runs at its own far-future instant so windows contain only its signals;
 * only the rule under test is enabled; windows and the 30-min cooldown are crossed by moving PnhClock (no sleeps).
 */
class AlertEvaluationIT extends StubbedProvidersTestBase {

    @Autowired
    AlertEvaluator evaluator;

    @Autowired
    AlertSignalCollector collector;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    NotificationIdGenerator ids;

    @Autowired
    PartitionService partitions;

    private String active1;
    private String active2;
    private String inactive;

    @BeforeEach
    void setUp() {
        jdbc.update(
                "UPDATE pnh_alert_rules SET enabled = FALSE, threshold = CASE rule_code WHEN 'STUCK_DELIVERY_SPIKE' THEN 10 "
                        + "WHEN 'PROVIDER_UNAVAILABLE' THEN 2 WHEN 'CHANNEL_FAILURE_RATE' THEN 30 ELSE 20 END, window_minutes = CASE rule_code "
                        + "WHEN 'PROVIDER_UNAVAILABLE' THEN 2 ELSE 10 END, cooldown_minutes = 30, min_sample = CASE rule_code "
                        + "WHEN 'CHANNEL_FAILURE_RATE' THEN 50 ELSE NULL END");
        jdbc.update("UPDATE pnh_alert_recipients SET is_active = FALSE");
        // each test runs at its own fixed time: alert state left by another test must not count as an open incident
        jdbc.update("DELETE FROM pnh_alert_events");
        active1 = MailSupport.uniqueAddress();
        active2 = MailSupport.uniqueAddress();
        inactive = MailSupport.uniqueAddress();
        for (String[] r : new String[][] {{active1, "TRUE"}, {active2, "TRUE"}, {inactive, "FALSE"}}) {
            jdbc.update(
                    "INSERT INTO pnh_alert_recipients (email, is_active, created_by, updated_by) VALUES (?, " + r[1]
                            + ", 't', 't')",
                    r[0]);
        }
    }

    @AfterEach
    void restore() {
        jdbc.update("UPDATE pnh_alert_rules SET enabled = TRUE");
    }

    private void enable(String rule) {
        jdbc.update("UPDATE pnh_alert_rules SET enabled = TRUE WHERE rule_code = ?", rule);
    }

    private void publish(Object event) {
        tx.executeWithoutResult(s -> context.publishEvent(event));
    }

    private List<Map<String, Object>> alerts(String rule, Instant from) {
        return jdbc.queryForList(
                "SELECT kind, channel_type, send_status, recipients::text AS r, measured_value FROM pnh_alert_events "
                        + "WHERE rule_code = ? AND occurred_at >= ? AND occurred_at < ? ORDER BY id",
                rule,
                java.sql.Timestamp.from(from),
                java.sql.Timestamp.from(from.plus(Duration.ofDays(1))));
    }

    @Test
    void stuckSpikeFiresOnceRespectsCooldownAndResolves() {
        Instant t0 = Instant.parse("2035-03-01T10:00:00Z");
        clock.setFixed(t0);
        enable("STUCK_DELIVERY_SPIKE");
        for (int i = 0; i < 10; i++) {
            publish(new NotificationStuckFailed(UUID.randomUUID(), "SMS", "DELIVERY_STATE_UNKNOWN", t0));
        }
        evaluator.evaluateNow();
        List<Map<String, Object>> fired = alerts("STUCK_DELIVERY_SPIKE", t0);
        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).get("kind")).isEqualTo("FIRED");
        assertThat(fired.get(0).get("send_status")).isEqualTo("SENT");
        assertThat((String) fired.get(0).get("r"))
                .contains(active1)
                .contains(active2)
                .doesNotContain(inactive);
        MimeMessage mail = MailSupport.awaitOne(active1);
        assertThat(MailSupport.to(active2)).hasSize(1);
        assertThat(MailSupport.to(inactive)).isEmpty();
        try {
            assertThat(mail.getSubject()).isEqualTo("[PNH][STUCK_DELIVERY_SPIKE] SMS – 10 in 10 min");
        } catch (jakarta.mail.MessagingException e) {
            throw new IllegalStateException(e);
        }
        assertThat(MailSupport.html(mail))
                .contains("/notifications?status=FAILED&amp;channel_type=SMS")
                .doesNotContain("+849");

        clock.setFixed(t0.plus(Duration.ofMinutes(5)));
        publish(new NotificationStuckFailed(UUID.randomUUID(), "SMS", "DELIVERY_STATE_UNKNOWN", clock.instant()));
        evaluator.evaluateNow();
        assertThat(alerts("STUCK_DELIVERY_SPIKE", t0)).hasSize(1); // cooldown

        clock.setFixed(t0.plus(Duration.ofMinutes(20))); // window empty → resolved once
        evaluator.evaluateNow();
        evaluator.evaluateNow();
        List<Map<String, Object>> all = alerts("STUCK_DELIVERY_SPIKE", t0);
        assertThat(all).extracting(m -> m.get("kind")).containsExactly("FIRED", "RESOLVED");
        assertThat(MailSupport.to(active1)).hasSize(2);
    }

    @Test
    void noRecipientsStillRecorded() {
        Instant t0 = Instant.parse("2035-04-01T10:00:00Z");
        clock.setFixed(t0);
        enable("INGEST_REJECTED_SPIKE");
        jdbc.update("UPDATE pnh_alert_recipients SET is_active = FALSE");
        for (int i = 0; i < 20; i++) {
            publish(new IngestRejected("c", "UNAUTHORIZED", t0));
        }
        evaluator.evaluateNow();
        List<Map<String, Object>> a = alerts("INGEST_REJECTED_SPIKE", t0);
        assertThat(a).hasSize(1);
        assertThat(a.get(0).get("send_status")).isEqualTo("NO_RECIPIENTS");
        assertThat(a.get(0).get("channel_type")).isNull();
    }

    @Test
    void signalsAreRecordedAfterCommitWithoutEventPublications() {
        Instant t0 = Instant.parse("2035-05-01T10:00:00Z");
        clock.setFixed(t0);
        String key = AlertSignalCollector.key(AlertRuleCode.INGEST_REJECTED_SPIKE, null, null);
        long before = collector.countLocal(key, t0);
        tx.executeWithoutResult(s -> {
            context.publishEvent(new IngestRejected("c", "UNAUTHORIZED", t0));
            assertThat(collector.countLocal(key, t0)).isEqualTo(before); // not before the commit
        });
        assertThat(collector.countLocal(key, t0)).isEqualTo(before + 1);
        tx.executeWithoutResult(s -> {
            context.publishEvent(new IngestRejected("c", "UNAUTHORIZED", t0));
            s.setRollbackOnly();
        });
        assertThat(collector.countLocal(key, t0)).isEqualTo(before + 1); // rolled back → no signal
        // registry-tracked after-commit listeners need a 2nd connection per event (pool deadlock under load)
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM event_publication WHERE listener_id LIKE '%AlertSignalCollector%'",
                        Integer.class))
                .isZero();
    }

    @Test
    void sendFailureIsRetriedNextRun() {
        Instant t0 = Instant.parse("2035-05-01T10:00:00Z");
        clock.setFixed(t0);
        enable("INGEST_REJECTED_SPIKE");
        long id = data.activeChannelConfig(
                "EMAIL", "SMTP", Map.of("host", "localhost", "port", 1, "from", "x@pnh.test"), Map.of());
        context.publishEvent(new ChannelConfigChanged("EMAIL", "SMTP", id));
        for (int i = 0; i < 20; i++) {
            publish(new IngestRejected("c", "UNAUTHORIZED", t0));
        }
        evaluator.evaluateNow();
        assertThat(alerts("INGEST_REJECTED_SPIKE", t0))
                .extracting(m -> m.get("send_status"))
                .containsExactly("SEND_FAILED");
        com.pnh.support.ProviderStubs.activate(data, context); // email provider back
        clock.setFixed(t0.plus(Duration.ofMinutes(1)));
        evaluator.evaluateNow();
        assertThat(alerts("INGEST_REJECTED_SPIKE", t0))
                .extracting(m -> m.get("send_status"))
                .containsExactly("SEND_FAILED", "SENT");
    }

    @Test
    void redisDownFallsBackToPostgresCounts() {
        Instant t0 = Instant.parse("2035-06-01T10:00:00Z");
        partitions.ensureMonths(t0, 1);
        clock.setFixed(t0);
        enable("STUCK_DELIVERY_SPIKE");
        for (int i = 0; i < 10; i++) {
            UUID id = ids.next();
            tracking.createReceived(new NewNotification(
                    id,
                    "alert-client",
                    "al-" + id,
                    "u",
                    "a@b.co",
                    "PUSH",
                    Category.STANDARD,
                    ContentMode.DIRECT,
                    null,
                    null,
                    null,
                    "t",
                    "b",
                    null,
                    null,
                    null));
            tracking.transition(StatusChange.of(id, NotificationStatus.RECEIVED, NotificationStatus.FAILED)
                    .error(ErrorCode.PROCESSING_TIMEOUT, "swept")
                    .build());
        }
        TestContainers.pauseRedis();
        try {
            evaluator.evaluateNow();
        } finally {
            TestContainers.unpauseRedis();
        }
        List<Map<String, Object>> a = alerts("STUCK_DELIVERY_SPIKE", t0);
        assertThat(a).hasSize(1);
        assertThat(a.get(0).get("channel_type")).isEqualTo("PUSH");
    }

    @Test
    void providerUnavailableFailureRateAndIngestRules() {
        Instant t0 = Instant.parse("2035-07-01T10:00:00Z");
        clock.setFixed(t0);
        enable("PROVIDER_UNAVAILABLE");
        enable("CHANNEL_FAILURE_RATE");
        enable("INGEST_REJECTED_SPIKE");
        context.publishEvent(new ProviderAvailabilityChanged("SMS", "ALERT_PROBE", "OPEN", t0));
        for (int i = 0; i < 100; i++) {
            publish(new NotificationOutcome(
                    UUID.randomUUID(),
                    "c",
                    "r" + i,
                    "u",
                    "PUSH",
                    "STANDARD",
                    i < 35 ? "FAILED" : "SENT",
                    null,
                    null,
                    1,
                    t0,
                    t0));
        }
        for (int i = 0; i < 20; i++) {
            publish(new IngestRejected("c", "VALIDATION_ERROR", t0));
        }
        clock.setFixed(t0.plus(Duration.ofMinutes(3)));
        evaluator.evaluateNow();
        assertThat(alerts("PROVIDER_UNAVAILABLE", t0))
                .extracting(m -> m.get("channel_type"))
                .containsExactly("SMS");
        assertThat(alerts("CHANNEL_FAILURE_RATE", t0))
                .extracting(m -> m.get("channel_type"))
                .containsExactly("PUSH");
        assertThat(alerts("INGEST_REJECTED_SPIKE", t0)).hasSize(1);
        context.publishEvent(new ProviderAvailabilityChanged("SMS", "ALERT_PROBE", "CLOSED", clock.instant()));
    }

    private void outcomes(String channel, Instant at, int failed, int sent) {
        for (int i = 0; i < failed + sent; i++) {
            publish(new NotificationOutcome(
                    UUID.randomUUID(),
                    "c",
                    "fr" + i,
                    "u",
                    channel,
                    "STANDARD",
                    i < failed ? "FAILED" : "SENT",
                    null,
                    null,
                    1,
                    at,
                    at));
        }
    }

    @Test
    void failureRateResolvesOnlyWhenTheRateClearsNotWhenVolumeDrops() {
        Instant t0 = Instant.parse("2035-08-01T10:00:00Z");
        clock.setFixed(t0);
        enable("CHANNEL_FAILURE_RATE"); // threshold 30 %, window 10 min, min_sample 50
        outcomes("EMAIL", t0, 40, 20); // 67 % of 60
        evaluator.evaluateNow();
        assertThat(alerts("CHANNEL_FAILURE_RATE", t0))
                .extracting(m -> m.get("kind"))
                .containsExactly("FIRED");

        // 10 min later the first burst left the window; still failing, but only 8 samples (< min_sample)
        Instant t1 = t0.plus(Duration.ofMinutes(11));
        clock.setFixed(t1);
        outcomes("EMAIL", t1, 7, 1);
        evaluator.evaluateNow();
        assertThat(alerts("CHANNEL_FAILURE_RATE", t0))
                .as("87.5 % failing is not 'resolved', even below min_sample")
                .extracting(m -> m.get("kind"))
                .containsExactly("FIRED");

        // the rate really clears
        Instant t2 = t1.plus(Duration.ofMinutes(11));
        clock.setFixed(t2);
        outcomes("EMAIL", t2, 1, 20);
        evaluator.evaluateNow();
        assertThat(alerts("CHANNEL_FAILURE_RATE", t0))
                .extracting(m -> m.get("kind"))
                .containsExactly("FIRED", "RESOLVED");
    }
}
