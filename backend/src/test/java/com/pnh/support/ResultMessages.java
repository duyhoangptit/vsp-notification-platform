package com.pnh.support;

import com.pnh.shared.infra.KafkaTopics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads result messages of one notification from pnh.notification.result. */
public final class ResultMessages {

    private ResultMessages() {}

    public static List<ConsumerRecord<String, String>> await(UUID notificationId, int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        TestContainers.KAFKA.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "results-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest"),
                new StringDeserializer(),
                new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaTopics.RESULT));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && found.size() < expected) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(300))) {
                    if (r.value() != null && r.value().contains(notificationId.toString())) {
                        found.add(r);
                    }
                }
            }
            // drain briefly to detect unexpected extra messages
            long extra = System.nanoTime() + Duration.ofMillis(800).toNanos();
            while (System.nanoTime() < extra) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                    if (r.value() != null && r.value().contains(notificationId.toString())) {
                        found.add(r);
                    }
                }
            }
        }
        return found;
    }

    public static String header(ConsumerRecord<?, ?> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
