package com.pnh.perf;

import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.pnh.coreapi.IngestRejected;
import com.pnh.shared.infra.KafkaTopics;
import com.pnh.support.ConsoleSession;
import com.pnh.support.MailSupport;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestContainers;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * SLO measurements (T181, fix G1) — outside the deterministic {@code verify} gate: {@code ./mvnw -Pperformance verify}.
 * Sizes are configurable with -Dpnh.perf.notifications / -Dpnh.perf.backlog. Writes target/reports/performance/slo.json.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
    "pnh.jobs.enabled=true", // real schedulers for SC-017
    "pnh.alerting.interval=20s",
    "pnh.alerting.initial-delay=5s",
    "pnh.resilience.call-timeout=5s",
    "spring.datasource.hikari.maximum-pool-size=50" // production sizing (the test profile uses a small pool)
})
class SloPerformanceTest extends StubbedProvidersTestBase {

    static final int N = Integer.getInteger("pnh.perf.notifications", 5000);
    static final int BACKLOG = Integer.getInteger("pnh.perf.backlog", 2000);
    static final Map<String, Object> REPORT = Collections.synchronizedMap(new LinkedHashMap<>());

    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper json;

    static double percentile(List<Long> values, double p) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private List<UUID> sendMany(ClientCreds c, int n, String channel, String category) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<UUID>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            futures.add(pool.submit(() -> notificationId(send(mvc, c, "OTP".equals(category)
                    ? json("client_request_id", "perf-otp-" + k + "-" + UUID.randomUUID(), "user_id", "p" + (k % 500), "channel_type",
                            channel, "recipient_target", MailSupport.uniqueAddress(), "category", "OTP", "otp_value", "1234",
                            "expires_at", "2099-01-01T00:00:00Z", "content", "{\"subject\":\"Code\",\"body\":\"<p>1234</p>\"}")
                    : json("client_request_id", "perf-" + k + "-" + UUID.randomUUID(), "user_id", "p" + (k % 500), "channel_type",
                            channel, "recipient_target", "+84901234567", "content", "{\"body\":\"perf\"}")))));
        }
        List<UUID> ids = new ArrayList<>();
        for (Future<UUID> f : futures) {
            ids.add(f.get());
        }
        pool.shutdown();
        return ids;
    }

    @Test
    @Order(1)
    void sc004_terminalWithin30Seconds_and_sc007_resultsWithin5Seconds() throws Exception {
        ClientCreds c = data.client("SEND", 10_000_000, true, "ACTIVE");
        Map<String, Long> consumedAt = new ConcurrentHashMap<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestContainers.KAFKA.getBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, "perf-" + UUID.randomUUID(),
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"), new StringDeserializer(), new StringDeserializer())) {
                consumer.subscribe(List.of(KafkaTopics.RESULT));
                while (!Thread.currentThread().isInterrupted()) {
                    for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                        String id = r.value().replaceAll(".*\"notification_id\":\"([^\"]+)\".*", "$1");
                        consumedAt.putIfAbsent(id, System.currentTimeMillis()); // wall time: measured against DB timestamps (SYSTEM clock)
                    }
                }
            }
        });
        Thread.sleep(2000); // consumer assignment (performance suite only; not part of the deterministic gate)
        List<UUID> ids = sendMany(c, N, "SMS", "STANDARD");
        await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(2)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM pnh_notifications_history WHERE client_id = ? AND status IN ('SENT','DELIVERED','FAILED')",
                Integer.class, c.clientId()) == ids.size());
        List<Long> toTerminal = jdbc.queryForList("""
                SELECT (extract(epoch FROM (e.created_at - h.created_at)) * 1000)::bigint FROM pnh_notifications_history h
                JOIN pnh_notification_events e ON e.notification_id = h.notification_id AND e.to_status IN ('SENT','FAILED')
                WHERE h.client_id = ?""", Long.class, c.clientId());
        double within30 = toTerminal.stream().filter(ms -> ms <= 30_000).count() * 100.0 / toTerminal.size();
        REPORT.put("SC-004", Map.of("notifications", ids.size(), "pct_terminal_within_30s", within30,
                "p95_ms", percentile(toTerminal, 95), "p99_ms", percentile(toTerminal, 99)));

        await().atMost(Duration.ofMinutes(2)).until(() -> ids.stream().allMatch(id -> consumedAt.containsKey(id.toString())));
        reader.interrupt();
        List<Long> resultLag = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT h.notification_id::text AS id, (extract(epoch FROM e.created_at) * 1000)::bigint AS at
                FROM pnh_notifications_history h JOIN pnh_notification_events e ON e.notification_id = h.notification_id
                  AND e.to_status IN ('SENT','FAILED') WHERE h.client_id = ?""", c.clientId())) {
            resultLag.add(consumedAt.get((String) row.get("id")) - ((Number) row.get("at")).longValue());
        }
        double within5 = resultLag.stream().filter(ms -> ms <= 5_000).count() * 100.0 / resultLag.size();
        REPORT.put("SC-007", Map.of("pct_results_within_5s", within5, "p95_ms", percentile(resultLag, 95), "max_ms",
                percentile(resultLag, 100)));
        assertThat(within30).as("SC-004: 99%% terminal within 30 s").isGreaterThanOrEqualTo(99.0);
        assertThat(within5).as("SC-007: results within 5 s").isEqualTo(100.0);
    }

    @Test
    @Order(2)
    void sc012_otpLatencyUnderStandardBacklog() throws Exception {
        ClientCreds c = data.client("SEND", 10_000_000, true, "ACTIVE");
        com.pnh.support.ProviderStubs.WIREMOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/sms/send")).atPriority(1)
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{\"message_id\":\"b\"}").withFixedDelay(300)));
        sendMany(c, BACKLOG, "SMS", "STANDARD");
        List<UUID> otps = sendMany(c, 50, "EMAIL", "OTP");
        await().atMost(Duration.ofMinutes(2)).until(() -> otps.stream().allMatch(id -> jdbc.queryForObject(
                "SELECT count(*) FROM pnh_notification_events WHERE notification_id = ? AND to_status = 'SENDING'", Integer.class, id) == 1));
        List<Long> lat = new ArrayList<>();
        for (UUID id : otps) {
            lat.add(jdbc.queryForObject("""
                    SELECT (extract(epoch FROM (e.created_at - h.created_at)) * 1000)::bigint FROM pnh_notifications_history h
                    JOIN pnh_notification_events e ON e.notification_id = h.notification_id AND e.to_status = 'SENDING'
                    WHERE h.notification_id = ?""", Long.class, id));
        }
        double p95 = percentile(lat, 95);
        REPORT.put("SC-012", Map.of("backlog", BACKLOG, "otp_count", otps.size(), "p95_accept_to_sending_ms", p95));
        com.pnh.support.ProviderStubs.reset();
        assertThat(p95).as("SC-012: OTP accepted→SENDING p95 < 5 s").isLessThan(5000);
    }

    @Test
    @Order(3)
    void sc010_dashboardUnderThreeSeconds() throws Exception {
        jdbc.update("""
                INSERT INTO pnh_notification_stats_hourly (bucket_hour, channel_type, client_id, status, count, recomputed_at)
                SELECT h, ch, 'perf-client-' || (g % 20), st, (random() * 1000)::bigint, now()
                FROM generate_series(date_trunc('hour', now()) - interval '30 days', date_trunc('hour', now()), interval '1 hour') h,
                     unnest(ARRAY['EMAIL','SMS','PUSH']) ch, unnest(ARRAY['SENT','DELIVERED','FAILED']) st, generate_series(1, 20) g
                ON CONFLICT DO NOTHING""");
        ConsoleSession s = ConsoleSession.login(mvc, data, "ADMIN");
        String from = Instant.now().minus(Duration.ofDays(30)).toString();
        String to = Instant.now().toString();
        long t0 = System.nanoTime();
        assertThat(s.perform(get("/console/api/dashboard/volume?granularity=HOUR&from=" + from + "&to=" + to)).getResponse().getStatus())
                .isEqualTo(200);
        long volumeMs = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        assertThat(s.perform(get("/console/api/dashboard/channel-rates?from=" + from + "&to=" + to)).getResponse().getStatus()).isEqualTo(200);
        long ratesMs = (System.nanoTime() - t0) / 1_000_000;
        REPORT.put("SC-010", Map.of("volume_ms", volumeMs, "channel_rates_ms", ratesMs));
        assertThat(volumeMs).isLessThan(3000);
        assertThat(ratesMs).isLessThan(3000);
    }

    @Test
    @Order(4)
    void sc017_alertEmailWithinThreeMinutes() {
        jdbc.update("UPDATE pnh_alert_recipients SET is_active = FALSE");
        String to = MailSupport.uniqueAddress();
        jdbc.update("INSERT INTO pnh_alert_recipients (email, is_active, created_by, updated_by) VALUES (?, TRUE, 'perf', 'perf')", to);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 25; i++) {
            tx.executeWithoutResult(st -> context.publishEvent(new IngestRejected("perf", "UNAUTHORIZED", clock.instant())));
        }
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(2)).until(() -> !MailSupport.to(to).isEmpty());
        long ms = System.currentTimeMillis() - start;
        REPORT.put("SC-017", Map.of("alert_email_ms", ms));
        assertThat(ms).isLessThanOrEqualTo(180_000);
    }

    @AfterAll
    static void writeReport() throws Exception {
        Path dir = Path.of("target/reports/performance");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("slo.json"), JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(REPORT));
    }
}
