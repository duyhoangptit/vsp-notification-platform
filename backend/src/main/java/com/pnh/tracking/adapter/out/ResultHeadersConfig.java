package com.pnh.tracking.adapter.out;

import com.pnh.shared.infra.KafkaTopics;
import com.pnh.tracking.NotificationResultEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;

/** Kafka headers of result messages: client_id, channel_type, status, event_id (contracts/kafka/README.md). */
@Configuration(proxyBeanMethods = false)
class ResultHeadersConfig {

    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .selectByType(NotificationResultEvent.class)
                .route(
                        NotificationResultEvent.class,
                        e -> org.springframework.modulith.events.RoutingTarget.forTarget(KafkaTopics.RESULT)
                                .andKey(e.routingKey()))
                .headers(NotificationResultEvent.class, e -> {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put(KafkaTopics.HEADER_CLIENT_ID, e.clientId());
                    if (e.channelType() != null) {
                        h.put(KafkaTopics.HEADER_CHANNEL_TYPE, e.channelType());
                    }
                    h.put(KafkaTopics.HEADER_STATUS, e.status());
                    h.put(KafkaTopics.HEADER_EVENT_ID, e.eventId().toString());
                    return h;
                })
                .build();
    }
}
