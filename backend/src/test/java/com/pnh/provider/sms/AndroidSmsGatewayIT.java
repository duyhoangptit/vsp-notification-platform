package com.pnh.provider.sms;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.provider.ChannelConfigChanged;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.CallbackSigner;
import com.pnh.support.ProviderStubs;
import com.pnh.support.StubbedProvidersTestBase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** T102 — SMS Gateway for Android emulated by WireMock. */
class AndroidSmsGatewayIT extends StubbedProvidersTestBase {

    static final String SIGNING_KEY = "gw-signing-key";

    @BeforeEach
    void useGateway() {
        data.activeChannelConfig(
                "SMS",
                "ANDROID_SMS_GATEWAY",
                Map.of("base_url", ProviderStubs.wiremockUrl(), "mode", "cloud"),
                Map.of("username", "gw", "password", "gw-pass", "webhook_signing_key", SIGNING_KEY));
        context.publishEvent(new ChannelConfigChanged("SMS", "ANDROID_SMS_GATEWAY", 0));
        ProviderStubs.WIREMOCK.stubFor(post(urlPathEqualTo("/3rdparty/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"{{jsonPath request.body '$.id'}}\",\"state\":\"Pending\"}")
                        .withTransformers("response-template")));
    }

    private UUID sendSms() throws Exception {
        UUID id = notificationId(send(
                mvc,
                data.client("SEND"),
                json(
                        "client_request_id",
                        "gw-" + UUID.randomUUID(),
                        "user_id",
                        "u",
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        "+84901234567",
                        "content",
                        "{\"body\":\"hello gw\"}")));
        awaitStatus(tracking, id, NotificationStatus.SENT);
        return id;
    }

    private MvcResult webhook(String event, UUID messageId, String reason, String signature) throws Exception {
        String body = "{\"deviceId\":\"d1\",\"event\":\"" + event
                + "\",\"id\":\"e1\",\"webhookId\":\"w1\",\"payload\":{\"messageId\":\"" + messageId
                + "\",\"recipient\":\"+84901234567\"" + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}}";
        long ts = clock.instant().getEpochSecond();
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/callbacks/sms/android-sms-gateway")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Timestamp", String.valueOf(ts))
                        .header(
                                "X-Signature",
                                signature == null ? CallbackSigner.androidGateway(SIGNING_KEY, ts, body) : signature))
                .andReturn();
    }

    @Test
    void sendUsesNotificationIdAsGatewayMessageId() throws Exception {
        UUID id = sendSms();
        assertThat(tracking.findById(id).orElseThrow().providerMessageId()).isEqualTo(id.toString());
        ProviderStubs.WIREMOCK.verify(postRequestedFor(urlPathEqualTo("/3rdparty/v1/messages"))
                .withRequestBody(equalToJson(
                        "{\"id\":\"" + id
                                + "\",\"textMessage\":{\"text\":\"hello gw\"},\"phoneNumbers\":[\"+84901234567\"]}",
                        true,
                        true)));
    }

    @Test
    void webhookEventsMapToStatuses() throws Exception {
        UUID delivered = sendSms();
        assertThat(webhook("sms:sent", delivered, null, null).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(tracking.findById(delivered).orElseThrow().status()).isEqualTo(NotificationStatus.SENT);
        assertThat(webhook("sms:delivered", delivered, null, null).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(tracking.findById(delivered).orElseThrow().status()).isEqualTo(NotificationStatus.DELIVERED);

        UUID failed = sendSms();
        webhook("sms:failed", failed, "RESULT_ERROR_GENERIC_FAILURE", null);
        var v = tracking.findById(failed).orElseThrow();
        assertThat(v.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(v.errorCode()).isEqualTo(ErrorCode.PROVIDER_REJECTED);
    }

    @Test
    void invalidSignatureIs401() throws Exception {
        UUID id = sendSms();
        assertThat(webhook("sms:delivered", id, null, "00ff").getResponse().getStatus())
                .isEqualTo(401);
        assertThat(tracking.findById(id).orElseThrow().status()).isEqualTo(NotificationStatus.SENT);
    }
}
