package com.pnh.coreapi.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pnh.coreapi.IngestCommitListener;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.shared.infra.KafkaTopics;
import com.pnh.support.KafkaSigner;
import com.pnh.support.ProviderStubs;
import com.pnh.support.SendSupport;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

/** T121 — a crash between intake commit and offset commit: the message is redelivered and absorbed by idempotency. */
@Import(IngestAckIT.CrashOnce.class)
class IngestAckIT extends StubbedProvidersTestBase {

    @TestComponent
    static class CrashOnce implements IngestCommitListener {
        final Set<String> crashFor = ConcurrentHashMap.newKeySet();
        final AtomicInteger crashes = new AtomicInteger();

        @Override
        public void afterIntakeCommitted(String clientRequestId) {
            if (crashFor.remove(clientRequestId)) {
                crashes.incrementAndGet();
                throw new IllegalStateException("simulated consumer crash before offset commit");
            }
        }
    }

    @Autowired
    KafkaTemplate<Object, Object> kafka;

    @Autowired
    CrashOnce crash;

    @Test
    void redeliveredMessageIsNotSentTwice() throws Exception {
        ClientCreds c = data.client("SEND");
        String req = "ack-" + UUID.randomUUID();
        String marker = "ack" + UUID.randomUUID().toString().substring(0, 6);
        String json = "{\"client_request_id\":\"" + req + "\",\"user_id\":\"u\",\"channel_type\":\"SMS\","
                + "\"recipient_target\":\"+84901234567\",\"content\":{\"body\":\"" + marker + "\"}}";
        crash.crashFor.add(req);
        byte[] value = json.getBytes(StandardCharsets.UTF_8);
        ProducerRecord<Object, Object> record = new ProducerRecord<>(KafkaTopics.INGEST, null, "u", value);
        record.headers().add(KafkaTopics.HEADER_CLIENT_ID, c.clientId().getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(
                        KafkaTopics.HEADER_SIGNATURE,
                        KafkaSigner.sign(c.clientId(), c.signingKey(), req, value)
                                .getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> tracking.findByClientRequest(c.clientId(), req).isPresent());
        var v = SendSupport.awaitStatus(
                tracking,
                tracking.findByClientRequest(c.clientId(), req).orElseThrow().notificationId(),
                NotificationStatus.SENT);
        await().atMost(Duration.ofSeconds(10)).until(() -> crash.crashes.get() == 1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pnh_notifications_history WHERE client_id=? AND client_request_id=?",
                        Integer.class,
                        c.clientId(),
                        req))
                .isEqualTo(1);
        assertThat(ProviderStubs.WIREMOCK.findAll(postRequestedFor(urlPathEqualTo("/sms/send"))
                        .withRequestBody(matchingJsonPath(
                                "$.message", com.github.tomakehurst.wiremock.client.WireMock.equalTo(marker)))))
                .hasSize(1);
        assertThat(v.status()).isEqualTo(NotificationStatus.SENT);
    }
}
