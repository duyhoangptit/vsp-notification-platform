package com.pnh.shared.infra;

import com.pnh.shared.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes a rejected ingest record (original key, value and headers) to the DLT with error headers. */
@Component
public class DltPublisher {

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public DltPublisher(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(ConsumerRecord<String, String> record, ErrorCode code, String message) {
        byte[] value = record.value() == null ? null : record.value().getBytes(StandardCharsets.UTF_8);
        ProducerRecord<Object, Object> out = new ProducerRecord<>(KafkaTopics.INGEST_DLT, null, record.key(), value);
        for (Header h : record.headers()) {
            out.headers().add(h);
        }
        out.headers().add(KafkaTopics.HEADER_ERROR_CODE, code.name().getBytes(StandardCharsets.UTF_8));
        String msg = message == null ? code.title() : message;
        out.headers().add(KafkaTopics.HEADER_ERROR_MESSAGE, msg.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(out).join();
    }
}
