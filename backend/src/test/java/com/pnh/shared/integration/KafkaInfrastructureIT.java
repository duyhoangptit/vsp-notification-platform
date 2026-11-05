package com.pnh.shared.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.infra.DltPublisher;
import com.pnh.shared.infra.KafkaTopics;
import com.pnh.support.IntegrationTestBase;
import com.pnh.support.TestContainers;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.Acknowledgment;

/** T033 */
@Import(KafkaInfrastructureIT.Probe.class)
class KafkaInfrastructureIT extends IntegrationTestBase {

    static final String TOPIC = "pnh.it.infra";

    @TestComponent
    static class Probe {
        final CountDownLatch handled = new CountDownLatch(1);
        final AtomicReference<Long> committedDuringHandler = new AtomicReference<>();

        @KafkaListener(
                id = "infra-probe",
                topics = TOPIC,
                containerFactory = "ingestListenerFactory",
                groupId = "infra-probe")
        void on(ConsumerRecord<String, String> record, Acknowledgment ack) {
            if (record.value().contains("poison")) {
                throw new IllegalArgumentException("cannot handle");
            }
            committedDuringHandler.set(committedOffset());
            ack.acknowledge();
            handled.countDown();
        }

        private static Long committedOffset() {
            try (var admin = org.apache.kafka.clients.admin.Admin.create(
                    Map.of("bootstrap.servers", TestContainers.KAFKA.getBootstrapServers()))) {
                var offsets = admin.listConsumerGroupOffsets("infra-probe")
                        .partitionsToOffsetAndMetadata()
                        .get();
                return offsets.values().stream()
                        .filter(java.util.Objects::nonNull)
                        .mapToLong(o -> o.offset())
                        .sum();
            } catch (Exception e) {
                return -1L;
            }
        }
    }

    @Autowired
    KafkaTemplate<Object, Object> kafka;

    @Autowired
    ProducerFactory<Object, Object> producerFactory;

    @Autowired
    DltPublisher dlt;

    @Autowired
    Probe probe;

    @Test
    void idempotentProducerSettings() {
        Map<String, Object> cfg = producerFactory.getConfigurationProperties();
        assertThat(String.valueOf(cfg.get(ProducerConfig.ACKS_CONFIG))).isEqualTo("all");
        assertThat(String.valueOf(cfg.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)))
                .isEqualTo("true");
    }

    @Test
    void manualAckCommitsOnlyAfterHandlerAndPoisonGoesToDlt() throws Exception {
        String marker = UUID.randomUUID().toString();
        kafka.send(TOPIC, "user-1", ("{\"ok\":\"" + marker + "\"}").getBytes(StandardCharsets.UTF_8))
                .get();
        assertThat(probe.handled.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(probe.committedDuringHandler.get()).isIn(0L, -1L);

        kafka.send(TOPIC, "user-2", ("{\"poison\":\"" + marker + "\"}").getBytes(StandardCharsets.UTF_8))
                .get();
        ConsumerRecord<String, String> dead = awaitDlt(marker);
        assertThat(dead.key()).isEqualTo("user-2");
        assertThat(header(dead, KafkaTopics.HEADER_ERROR_CODE)).isNotBlank();
        assertThat(header(dead, KafkaTopics.HEADER_ERROR_MESSAGE)).isNotBlank();
    }

    @Test
    void dltPublisherKeepsKeyAndHeaders() {
        String marker = UUID.randomUUID().toString();
        ConsumerRecord<String, String> original =
                new ConsumerRecord<>(KafkaTopics.INGEST, 0, 0, "user-9", "{\"m\":\"" + marker + "\"}");
        original.headers().add(KafkaTopics.HEADER_CLIENT_ID, "client-a".getBytes(StandardCharsets.UTF_8));
        dlt.publish(original, ErrorCode.UNAUTHORIZED, "bad signature");
        ConsumerRecord<String, String> dead = awaitDlt(marker);
        assertThat(dead.key()).isEqualTo("user-9");
        assertThat(header(dead, KafkaTopics.HEADER_CLIENT_ID)).isEqualTo("client-a");
        assertThat(header(dead, KafkaTopics.HEADER_ERROR_CODE)).isEqualTo("UNAUTHORIZED");
        assertThat(header(dead, KafkaTopics.HEADER_ERROR_MESSAGE)).isEqualTo("bad signature");
    }

    static String header(ConsumerRecord<?, ?> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    static ConsumerRecord<String, String> awaitDlt(String marker) {
        return com.pnh.support.DltMessages.await(marker);
    }
}
