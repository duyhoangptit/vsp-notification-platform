package com.pnh.contract;

import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.shared.domain.NotificationStatus;
import com.pnh.shared.infra.KafkaTopics;
import com.pnh.support.ResultMessages;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/** T099 */
class ResultMessageContractIT extends StubbedProvidersTestBase {

    @Test
    void resultMessageMatchesSchemaKeyAndHeaders() throws Exception {
        ClientCreds c = data.client("SEND");
        UUID id = notificationId(send(
                mvc,
                c,
                json(
                        "client_request_id",
                        "rm-" + UUID.randomUUID(),
                        "user_id",
                        "user_77",
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        "+84901234567",
                        "content",
                        "{\"body\":\"hi\"}")));
        awaitStatus(tracking, id, NotificationStatus.SENT);
        var records = ResultMessages.await(id, 1, Duration.ofSeconds(20));
        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> r = records.get(0);
        ContractSupport.assertMatchesSchema("result.schema.json", r.value());
        assertThat(r.key()).isEqualTo("user_77");
        assertThat(ResultMessages.header(r, KafkaTopics.HEADER_CLIENT_ID)).isEqualTo(c.clientId());
        assertThat(ResultMessages.header(r, KafkaTopics.HEADER_CHANNEL_TYPE)).isEqualTo("SMS");
        assertThat(ResultMessages.header(r, KafkaTopics.HEADER_STATUS)).isEqualTo("SENT");
        assertThat(ResultMessages.header(r, KafkaTopics.HEADER_EVENT_ID)).isNotBlank();
        // exactly the contract headers: no framework type headers (__TypeId__ / spring_json_header_types)
        List<String> headerKeys = new ArrayList<>();
        r.headers().forEach(h -> headerKeys.add(h.key()));
        assertThat(headerKeys)
                .containsExactlyInAnyOrder(
                        KafkaTopics.HEADER_CLIENT_ID,
                        KafkaTopics.HEADER_CHANNEL_TYPE,
                        KafkaTopics.HEADER_STATUS,
                        KafkaTopics.HEADER_EVENT_ID);
        assertThat(r.value()).contains("\"sequence\":1").contains("\"status\":\"SENT\"");
    }
}
