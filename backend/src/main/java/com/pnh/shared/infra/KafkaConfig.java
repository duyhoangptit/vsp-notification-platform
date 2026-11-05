package com.pnh.shared.infra;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.SimpleKafkaHeaderMapper;
import org.springframework.kafka.support.converter.ByteArrayJacksonJsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.messaging.Message;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka infrastructure (T052, T124). Producer settings (acks=all, idempotence, String key = user_id, byte[] value)
 * come from application.yml; Modulith externalization serializes events with Jackson into the byte[] value.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    /**
     * Replaces Modulith's externalization converter: result messages carry exactly the contract headers
     * (contracts/kafka/README.md) as plain UTF-8 strings, without Spring type headers ({@code __TypeId__} with an
     * internal class name, {@code spring_json_header_types}) that would couple consumers to hub internals.
     */
    @Bean
    RecordMessageConverter resultMessageConverter(JsonMapper jsonMapper) {
        ByteArrayJacksonJsonMessageConverter converter = new ByteArrayJacksonJsonMessageConverter(jsonMapper) {
            @Override
            protected Headers initialRecordHeaders(Message<?> message) {
                return new RecordHeaders(); // no type id header
            }
        };
        SimpleKafkaHeaderMapper headers = new SimpleKafkaHeaderMapper(
                KafkaTopics.HEADER_CLIENT_ID,
                KafkaTopics.HEADER_CHANNEL_TYPE,
                KafkaTopics.HEADER_STATUS,
                KafkaTopics.HEADER_EVENT_ID);
        headers.setMapAllStringsOut(true);
        converter.setHeaderMapper(headers);
        return converter;
    }

    @Bean
    NewTopic ingestTopic(@Value("${pnh.kafka.topic-partitions:12}") int partitions) {
        return TopicBuilder.name(KafkaTopics.INGEST)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic ingestOtpTopic(@Value("${pnh.kafka.otp-topic-partitions:6}") int partitions) {
        return TopicBuilder.name(KafkaTopics.INGEST_OTP)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic resultTopic(@Value("${pnh.kafka.topic-partitions:12}") int partitions) {
        return TopicBuilder.name(KafkaTopics.RESULT)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic dltTopic() {
        return TopicBuilder.name(KafkaTopics.INGEST_DLT)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /** Ingest consumers: String JSON values, manual ack after the intake transaction commits (R5). */
    @Bean
    ConsumerFactory<String, String> ingestConsumerFactory(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>(properties.buildConsumerProperties());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> ingestListenerFactory(
            ConsumerFactory<String, String> ingestConsumerFactory,
            org.springframework.kafka.core.ProducerFactory<Object, Object> producerFactory,
            @Value("${pnh.kafka.ingest-concurrency:3}") int concurrency) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(ingestConsumerFactory);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // undeserializable records → DLT immediately, never retried (T124)
        // ingest values are Strings; the shared producer writes byte[] → dedicated String-valued template for the DLT
        KafkaTemplate<String, String> dltTemplate =
                new KafkaTemplate<>(new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(
                        producerFactory.getConfigurationProperties(),
                        new org.apache.kafka.common.serialization.StringSerializer(),
                        new org.apache.kafka.common.serialization.StringSerializer()));
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                dltTemplate, (record, ex) -> new TopicPartition(KafkaTopics.INGEST_DLT, -1));
        recoverer.setHeadersFunction((record, ex) -> {
            org.apache.kafka.common.header.internals.RecordHeaders headers =
                    new org.apache.kafka.common.header.internals.RecordHeaders();
            headers.add(
                    KafkaTopics.HEADER_ERROR_CODE,
                    "VALIDATION_ERROR".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            headers.add(
                    KafkaTopics.HEADER_ERROR_MESSAGE,
                    "Unreadable message".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return headers;
        });
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L));
        errorHandler.addNotRetryableExceptions(DeserializationException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
