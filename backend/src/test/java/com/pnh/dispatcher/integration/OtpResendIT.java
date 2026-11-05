package com.pnh.dispatcher.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.ProviderStubs;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T113 — OTP resends are new client requests; duplicates of one request id are absorbed. */
class OtpResendIT extends StubbedProvidersTestBase {

    private String otp(String requestId, String marker) {
        return json(
                "client_request_id",
                requestId,
                "user_id",
                "u",
                "channel_type",
                "SMS",
                "recipient_target",
                "+84901234567",
                "category",
                "OTP",
                "otp_value",
                "246810",
                "expires_at",
                "2099-01-01T00:00:00Z",
                "content",
                "{\"body\":\"" + marker + " 246810\"}");
    }

    private int calls(String marker) {
        return ProviderStubs.WIREMOCK
                .findAll(postRequestedFor(urlPathEqualTo("/sms/send"))
                        .withRequestBody(matchingJsonPath(
                                "$.message", com.github.tomakehurst.wiremock.client.WireMock.containing(marker))))
                .size();
    }

    @Test
    void sameRequestIdOnceNewIdsEach() throws Exception {
        ClientCreds c = data.client("SEND");
        String marker = "m" + UUID.randomUUID().toString().substring(0, 6);
        String req = "or-" + UUID.randomUUID();
        UUID first = notificationId(send(mvc, c, otp(req, marker)));
        UUID again = notificationId(send(mvc, c, otp(req, marker)));
        assertThat(again).isEqualTo(first);
        awaitStatus(tracking, first, NotificationStatus.SENT);
        UUID second = notificationId(send(mvc, c, otp("or-" + UUID.randomUUID(), marker)));
        UUID third = notificationId(send(mvc, c, otp("or-" + UUID.randomUUID(), marker)));
        awaitStatus(tracking, second, NotificationStatus.SENT);
        awaitStatus(tracking, third, NotificationStatus.SENT);
        assertThat(calls(marker)).isEqualTo(3);
    }
}
