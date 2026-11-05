package com.pnh.coreapi.integration;

import static com.pnh.support.SendSupport.awaitStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.pnh.contract.ContractSupport;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.shared.infra.KafkaTopics;
import com.pnh.support.KafkaSigner;
import com.pnh.support.ResultMessages;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import com.pnh.tracking.NotificationView;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/** T120 — asynchronous ingestion (US4). Messages are signed with the per-client key (fix U2). */
class KafkaIngestIT extends StubbedProvidersTestBase {

    @Autowired
    KafkaTemplate<Object, Object> kafka;

    private void publish(
            String topic, String key, ClientCreds c, String requestId, String json, String signatureOverride)
            throws Exception {
        byte[] value = json.getBytes(StandardCharsets.UTF_8);
        ProducerRecord<Object, Object> record = new ProducerRecord<>(topic, null, key, value);
        if (c != null) {
            record.headers().add(KafkaTopics.HEADER_CLIENT_ID, c.clientId().getBytes(StandardCharsets.UTF_8));
            String sig = signatureOverride != null
                    ? signatureOverride
                    : KafkaSigner.sign(c.clientId(), c.signingKey(), requestId, value);
            record.headers().add(KafkaTopics.HEADER_SIGNATURE, sig.getBytes(StandardCharsets.UTF_8));
        }
        kafka.send(record).get();
    }

    private Optional<NotificationView> find(ClientCreds c, String requestId) {
        return tracking.findByClientRequest(c.clientId(), requestId);
    }

    private NotificationView awaitCreated(ClientCreds c, String requestId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> find(c, requestId).isPresent());
        return find(c, requestId).orElseThrow();
    }

    private static String sms(String requestId, String userId) {
        return "{\"client_request_id\":\"" + requestId + "\",\"user_id\":\"" + userId + "\",\"channel_type\":\"SMS\","
                + "\"recipient_target\":\"+84901234567\",\"content\":{\"body\":\"from kafka\"}}";
    }

    @Test
    void validMessageDeliveredDuplicateIgnoredUnkeyedAccepted() throws Exception {
        ClientCreds c = data.client("SEND");
        String req = "k-" + UUID.randomUUID();
        publish(KafkaTopics.INGEST, "user-k", c, req, sms(req, "user-k"), null);
        publish(KafkaTopics.INGEST, "user-k", c, req, sms(req, "user-k"), null); // duplicate
        NotificationView v = awaitStatus(tracking, awaitCreated(c, req).notificationId(), NotificationStatus.SENT);
        assertThat(ResultMessages.await(v.notificationId(), 1, Duration.ofSeconds(20)))
                .hasSize(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pnh_notifications_history WHERE client_id=? AND client_request_id=?",
                        Integer.class,
                        c.clientId(),
                        req))
                .isEqualTo(1);

        String unkeyed = "k-" + UUID.randomUUID();
        publish(KafkaTopics.INGEST, null, c, unkeyed, sms(unkeyed, "user-k"), null);
        awaitStatus(tracking, awaitCreated(c, unkeyed).notificationId(), NotificationStatus.SENT);
    }

    @Test
    void invalidButReadableMessageBecomesFailedNotificationAndResult() throws Exception {
        ClientCreds c = data.client("SEND,TRACK");
        String req = "bad-" + UUID.randomUUID();
        String json = "{\"client_request_id\":\"" + req + "\",\"channel_type\":\"SMS\",\"content\":{\"body\":\"x\"}}";
        publish(KafkaTopics.INGEST, null, c, req, json, null);
        NotificationView v = awaitCreated(c, req);
        assertThat(v.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(v.userId()).isNull();
        assertThat(v.recipientTarget()).isNull();
        String track = mvc.perform(get("/api/v1/notifications/track/" + req).header("Authorization", c.basic()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(track).contains("\"status\":\"FAILED\"").contains("\"error_code\":\"VALIDATION_ERROR\"");
        var results = ResultMessages.await(v.notificationId(), 1, Duration.ofSeconds(20));
        assertThat(results).hasSize(1);
        ContractSupport.assertMatchesSchema("result.schema.json", results.get(0).value());
        assertThat(results.get(0).value()).contains("\"user_id\":null");
        assertThat(results.get(0).key()).isEqualTo(c.clientId() + ":" + req);

        // template error with readable user → FAILED with that code
        String req2 = "bad2-" + UUID.randomUUID();
        publish(
                KafkaTopics.INGEST,
                "u2",
                c,
                req2,
                "{\"client_request_id\":\"" + req2 + "\",\"user_id\":\"u2\",\"channel_type\":\"EMAIL\","
                        + "\"recipient_target\":\"a@b.co\",\"template_code\":\"NO_SUCH_TEMPLATE\"}",
                null);
        NotificationView v2 = awaitCreated(c, req2);
        assertThat(v2.errorCode().name()).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(v2.userId()).isEqualTo("u2");

        // resending the same request id → no second notification
        publish(KafkaTopics.INGEST, null, c, req, json, null);
        String marker = "after-" + UUID.randomUUID();
        publish(KafkaTopics.INGEST, null, c, marker, sms(marker, "u"), null);
        awaitCreated(
                c, marker); // processed after the duplicate (same partition order not guaranteed un-keyed; count below)
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pnh_notifications_history WHERE client_id=? AND client_request_id=?",
                        Integer.class,
                        c.clientId(),
                        req))
                .isEqualTo(1);
    }

    @Test
    void unreadableOrUnauthenticatedMessagesGoToDlt() throws Exception {
        ClientCreds c = data.client("SEND");
        ClientCreds other = data.client("SEND");
        String garbage = "not-json-" + UUID.randomUUID();
        publish(KafkaTopics.INGEST, "u", c, "x", garbage, "v1=00");
        assertThat(com.pnh.support.DltMessages.await(garbage).headers().lastHeader(KafkaTopics.HEADER_ERROR_CODE))
                .isNotNull();

        String unknown = "uk-" + UUID.randomUUID();
        ProducerRecord<Object, Object> r = new ProducerRecord<>(
                KafkaTopics.INGEST, null, "u", sms(unknown, "u").getBytes(StandardCharsets.UTF_8));
        r.headers().add(KafkaTopics.HEADER_CLIENT_ID, "no-such-client".getBytes(StandardCharsets.UTF_8));
        kafka.send(r).get();
        assertDlt(unknown, "UNAUTHORIZED");

        String missingSig = "ms-" + UUID.randomUUID();
        ProducerRecord<Object, Object> m = new ProducerRecord<>(
                KafkaTopics.INGEST, null, "u", sms(missingSig, "u").getBytes(StandardCharsets.UTF_8));
        m.headers().add(KafkaTopics.HEADER_CLIENT_ID, c.clientId().getBytes(StandardCharsets.UTF_8));
        kafka.send(m).get();
        assertDlt(missingSig, "UNAUTHORIZED");

        String wrongKey = "wk-" + UUID.randomUUID();
        publish(
                KafkaTopics.INGEST,
                "u",
                c,
                wrongKey,
                sms(wrongKey, "u"),
                KafkaSigner.sign(
                        c.clientId(),
                        other.signingKey(),
                        wrongKey,
                        sms(wrongKey, "u").getBytes(StandardCharsets.UTF_8)));
        assertDlt(wrongKey, "UNAUTHORIZED");

        String tampered = "tp-" + UUID.randomUUID();
        String sig = KafkaSigner.sign(c.clientId(), c.signingKey(), tampered, sms(tampered, "u"));
        publish(KafkaTopics.INGEST, "u", c, tampered, sms(tampered, "u").replace("from kafka", "tampered!"), sig);
        assertDlt(tampered, "UNAUTHORIZED");
        assertThat(find(c, tampered)).isEmpty();
    }

    @Test
    void otpLaneIsProcessedAsOtp() throws Exception {
        ClientCreds c = data.client("SEND");
        String req = "lane-" + UUID.randomUUID();
        String json = "{\"client_request_id\":\"" + req
                + "\",\"user_id\":\"u\",\"channel_type\":\"SMS\",\"recipient_target\":\"+84901234567\","
                + "\"category\":\"OTP\",\"otp_value\":\"908070\",\"expires_at\":\"2099-01-01T00:00:00Z\",\"content\":{\"body\":\"OTP 908070\"}}";
        publish(KafkaTopics.INGEST_OTP, "u", c, req, json, null);
        NotificationView v = awaitStatus(tracking, awaitCreated(c, req).notificationId(), NotificationStatus.SENT);
        assertThat(v.category().name()).isEqualTo("OTP");
        assertThat(v.compiledContent()).isEqualTo("OTP ******");
    }

    private static void assertDlt(String marker, String code) {
        ConsumerRecord<String, String> dead = com.pnh.support.DltMessages.await(marker);
        assertThat(new String(
                        dead.headers().lastHeader(KafkaTopics.HEADER_ERROR_CODE).value(), StandardCharsets.UTF_8))
                .isEqualTo(code);
    }
}
