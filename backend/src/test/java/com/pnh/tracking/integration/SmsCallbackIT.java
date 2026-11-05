package com.pnh.tracking.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.CallbackSigner;
import com.pnh.support.ProviderStubs;
import com.pnh.support.ResultMessages;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.tracking.NotificationView;
import com.pnh.tracking.StatusChange;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** T101 — generic HMAC SMS delivery reports. */
class SmsCallbackIT extends StubbedProvidersTestBase {

    private UUID sentSms() throws Exception {
        UUID id = notificationId(send(
                mvc,
                data.client("SEND"),
                json(
                        "client_request_id",
                        "cb-" + UUID.randomUUID(),
                        "user_id",
                        "u",
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        "+84901234567",
                        "content",
                        "{\"body\":\"hi\"}")));
        awaitStatus(tracking, id, NotificationStatus.SENT);
        return id;
    }

    private MvcResult callback(String body, String signature) throws Exception {
        long ts = clock.instant().getEpochSecond();
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/callbacks/sms/GENERIC_HTTP_SMS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-PNH-Timestamp", String.valueOf(ts))
                        .header(
                                "X-PNH-Signature",
                                signature == null
                                        ? CallbackSigner.generic(ProviderStubs.SMS_CALLBACK_SECRET, ts, body)
                                        : signature))
                .andReturn();
    }

    private static String report(String messageId, String requestId, String status) {
        return "{\"message_id\":\"" + messageId + "\""
                + (requestId == null ? "" : ",\"request_id\":\"" + requestId + "\"") + ",\"status\":\"" + status
                + "\",\"reported_at\":\"2026-11-03T10:00:00Z\"}";
    }

    private int events(UUID id, String type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pnh_notification_events WHERE notification_id=? AND event_type=?",
                Integer.class,
                id,
                type);
    }

    @Test
    void verifiedDeliveredReportMovesSentToDelivered() throws Exception {
        UUID id = sentSms();
        String msgId = tracking.findById(id).orElseThrow().providerMessageId();
        assertThat(callback(report(msgId, null, "DELIVERED"), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);
        assertThat(tracking.findById(id).orElseThrow().status()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(events(id, "CALLBACK_RECEIVED")).isEqualTo(1);
        // duplicate → idempotent, ignored
        assertThat(callback(report(msgId, null, "DELIVERED"), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);
        assertThat(events(id, "CALLBACK_IGNORED")).isEqualTo(1);
    }

    @Test
    void badSignatureIs401AndChangesNothing() throws Exception {
        UUID id = sentSms();
        String msgId = tracking.findById(id).orElseThrow().providerMessageId();
        assertThat(callback(report(msgId, id.toString(), "DELIVERED"), "sha256=deadbeef")
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(tracking.findById(id).orElseThrow().status()).isEqualTo(NotificationStatus.SENT);
        assertThat(events(id, "CALLBACK_IGNORED")).isEqualTo(1);
    }

    @Test
    void unknownMessageIs204WithoutChange() throws Exception {
        assertThat(callback(report("no-such-message", null, "DELIVERED"), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);
    }

    @Test
    void deliveredAfterUnknownStateFailureCorrectsIt() throws Exception {
        UUID id = sentSms();
        // simulate the FR-041 situation directly
        jdbc.update(
                "UPDATE pnh_notifications_history SET status='FAILED', error_code='DELIVERY_STATE_UNKNOWN' WHERE notification_id=?",
                id);
        String msgId = tracking.findById(id).orElseThrow().providerMessageId();
        callback(report(msgId, null, "DELIVERED"), null);
        NotificationView v = tracking.findById(id).orElseThrow();
        assertThat(v.status()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(ResultMessages.await(id, 2, Duration.ofSeconds(20)).stream().map(r -> r.value()))
                .anySatisfy(val -> assertThat(val).contains("\"status\":\"DELIVERED\""));

        UUID other = sentSms();
        tracking.transition(StatusChange.of(other, NotificationStatus.SENT, NotificationStatus.FAILED)
                .verifiedCallback()
                .error(ErrorCode.BOUNCED, "bounce")
                .build());
        callback(report(tracking.findById(other).orElseThrow().providerMessageId(), null, "DELIVERED"), null);
        assertThat(tracking.findById(other).orElseThrow().status()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void reportWhileStillSendingDeliversAndLateAckPublishesNoSent() throws Exception {
        ProviderStubs.WIREMOCK.stubFor(post(urlPathEqualTo("/sms/send"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message_id\":\"slow-1\"}")
                        .withFixedDelay(1200)));
        UUID id = notificationId(send(
                mvc,
                data.client("SEND"),
                json(
                        "client_request_id",
                        "slow-" + UUID.randomUUID(),
                        "user_id",
                        "u",
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        "+84901234567",
                        "content",
                        "{\"body\":\"hi\"}")));
        awaitStatus(tracking, id, NotificationStatus.SENDING);
        assertThat(callback(report("slow-1", id.toString(), "DELIVERED"), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);
        assertThat(tracking.findById(id).orElseThrow().status()).isEqualTo(NotificationStatus.DELIVERED);
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> "slow-1".equals(tracking.findById(id).orElseThrow().providerMessageId()));
        assertThat(tracking.findById(id).orElseThrow().status()).isEqualTo(NotificationStatus.DELIVERED);
        var results = ResultMessages.await(id, 1, Duration.ofSeconds(20));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).value()).contains("\"status\":\"DELIVERED\"");
    }
}
