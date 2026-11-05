package com.pnh.provider.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.pnh.provider.application.ProviderResilienceFactory;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.ProviderStubs;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import com.pnh.tracking.NotificationView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** T075 */
class DeliveryResilienceIT extends StubbedProvidersTestBase {

    @Autowired
    ProviderResilienceFactory resilience;

    @AfterEach
    void closeCircuit() {
        resilience.reset("SMS", "GENERIC_HTTP_SMS");
    }

    private UUID sms(ClientCreds c, String phone) throws Exception {
        return notificationId(send(
                mvc,
                c,
                json(
                        "client_request_id",
                        "r-" + UUID.randomUUID(),
                        "user_id",
                        "u",
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        phone,
                        "content",
                        "{\"body\":\"hello\"}")));
    }

    private int events(UUID id, String type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pnh_notification_events WHERE notification_id=? AND event_type=?",
                Integer.class,
                id,
                type);
    }

    @Test
    void transientFailuresAreRetriedUntilSuccess() throws Exception {
        var w = ProviderStubs.WIREMOCK;
        w.stubFor(post(urlPathEqualTo("/sms/send"))
                .inScenario("t")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("one"));
        w.stubFor(post(urlPathEqualTo("/sms/send"))
                .inScenario("t")
                .whenScenarioStateIs("one")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("two"));
        w.stubFor(post(urlPathEqualTo("/sms/send"))
                .inScenario("t")
                .whenScenarioStateIs("two")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message_id\":\"ok-3\"}")));
        UUID id = sms(data.client("SEND"), "+84901000503");
        NotificationView v = awaitStatus(tracking, id, NotificationStatus.SENT);
        assertThat(v.retryCount()).isEqualTo(2);
        assertThat(v.providerMessageId()).isEqualTo("ok-3");
        assertThat(events(id, "ATTEMPT_FAILED")).isEqualTo(2);
    }

    @Test
    void permanentErrorIsNotRetried() throws Exception {
        ProviderStubs.WIREMOCK.stubFor(post(urlPathEqualTo("/sms/send"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath(
                        "$.to", com.github.tomakehurst.wiremock.client.WireMock.equalTo("+84901000400")))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"INVALID_NUMBER\",\"message\":\"bad number\"}")));
        UUID id = sms(data.client("SEND"), "+84901000400");
        NotificationView v = awaitStatus(tracking, id, NotificationStatus.FAILED);
        assertThat(v.errorCode()).isEqualTo(ErrorCode.INVALID_RECIPIENT);
        assertThat(v.retryCount()).isZero();
        ProviderStubs.WIREMOCK.verify(
                1,
                postRequestedFor(urlPathEqualTo("/sms/send"))
                        .withRequestBody(matchingJsonPath(
                                "$.to", com.github.tomakehurst.wiremock.client.WireMock.equalTo("+84901000400"))));
    }

    @Test
    void sustainedOutageOpensCircuitAndOtherChannelsAreUnaffected() throws Exception {
        ProviderStubs.WIREMOCK.stubFor(post(urlPathEqualTo("/sms/send"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(503)));
        ClientCreds c = data.client("SEND", 10_000, true, "ACTIVE");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ids.add(sms(c, "+8490100" + String.format("%04d", i)));
        }
        for (UUID id : ids) {
            awaitStatus(tracking, id, NotificationStatus.FAILED, Duration.ofSeconds(30));
        }
        assertThat(resilience
                        .forProvider("SMS", "GENERIC_HTTP_SMS")
                        .circuitBreaker()
                        .getState()
                        .name())
                .isEqualTo("OPEN");
        int callsBefore = ProviderStubs.WIREMOCK
                .findAll(postRequestedFor(urlPathEqualTo("/sms/send")))
                .size();
        UUID fastFail = sms(c, "+84901009999");
        NotificationView v = awaitStatus(tracking, fastFail, NotificationStatus.FAILED);
        assertThat(v.errorCode()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
        assertThat(ProviderStubs.WIREMOCK.findAll(postRequestedFor(urlPathEqualTo("/sms/send"))))
                .hasSize(callsBefore);

        String email = data.template("EMAIL", "STANDARD", "s", null, "<p>x</p>");
        UUID mail = notificationId(send(
                mvc,
                c,
                json(
                        "client_request_id",
                        "e-" + UUID.randomUUID(),
                        "user_id",
                        "u",
                        "channel_type",
                        "EMAIL",
                        "recipient_target",
                        "a@b.co",
                        "template_code",
                        email)));
        awaitStatus(tracking, mail, NotificationStatus.SENT);
    }
}
