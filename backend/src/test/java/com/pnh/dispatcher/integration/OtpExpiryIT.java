package com.pnh.dispatcher.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.ProviderStubs;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import com.pnh.tracking.NotificationView;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** T110 — OTP expiry rules (FR-029, R27); time is moved with PnhClock, never with sleeps. */
class OtpExpiryIT extends StubbedProvidersTestBase {

    private static String rid() {
        return "otp-" + UUID.randomUUID();
    }

    private static String code(MvcResult r) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(), "$.code");
    }

    @Test
    void directOtpNeedsExpiryAndValue() throws Exception {
        ClientCreds c = data.client("SEND");
        assertThat(code(send(
                        mvc,
                        c,
                        json(
                                "client_request_id",
                                rid(),
                                "user_id",
                                "u",
                                "channel_type",
                                "SMS",
                                "recipient_target",
                                "+84901234567",
                                "category",
                                "OTP",
                                "otp_value",
                                "123456",
                                "content",
                                "{\"body\":\"Code 123456\"}"))))
                .isEqualTo("OTP_EXPIRY_REQUIRED");
        assertThat(code(send(
                        mvc,
                        c,
                        json(
                                "client_request_id",
                                rid(),
                                "user_id",
                                "u",
                                "channel_type",
                                "SMS",
                                "recipient_target",
                                "+84901234567",
                                "category",
                                "OTP",
                                "expires_at",
                                "2099-01-01T00:00:00Z",
                                "content",
                                "{\"body\":\"Code\"}"))))
                .isEqualTo("OTP_VALUE_REQUIRED");
    }

    @Test
    void templateExpiryVariableDerivesExpiry() throws Exception {
        clock.setFixed(Instant.parse("2026-11-03T13:22:00Z"));
        String tpl = data.template(
                com.pnh.support.TestDataFactory.unique("OTPT").toUpperCase().replace('-', '_'),
                "SMS",
                "OTP",
                false,
                "otp_code",
                "expired_minutes",
                null,
                null,
                "Ma ${otp_code}, ${expired_minutes} phut",
                "otp_code",
                "expired_minutes");
        ClientCreds c = data.client("SEND");
        MvcResult derived = send(
                mvc,
                c,
                json(
                        "client_request_id",
                        rid(),
                        "user_id",
                        "u",
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        "+84901234567",
                        "template_code",
                        tpl,
                        "variables",
                        "{\"otp_code\":\"654321\",\"expired_minutes\":\"5\"}"));
        assertThat(derived.getResponse().getStatus()).isEqualTo(202);
        assertThat(tracking.findById(notificationId(derived)).orElseThrow().expiresAt())
                .isEqualTo(Instant.parse("2026-11-03T13:27:00Z"));

        MvcResult explicit = send(
                mvc,
                c,
                json(
                        "client_request_id",
                        rid(),
                        "user_id",
                        "u",
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        "+84901234567",
                        "template_code",
                        tpl,
                        "expires_at",
                        "2026-11-03T13:40:00Z",
                        "variables",
                        "{\"otp_code\":\"654321\",\"expired_minutes\":\"5\"}"));
        assertThat(tracking.findById(notificationId(explicit)).orElseThrow().expiresAt())
                .isEqualTo(Instant.parse("2026-11-03T13:40:00Z"));

        for (String bad : new String[] {"abc", "0", "1441"}) {
            MvcResult r = send(
                    mvc,
                    c,
                    json(
                            "client_request_id",
                            rid(),
                            "user_id",
                            "u",
                            "channel_type",
                            "SMS",
                            "recipient_target",
                            "+84901234567",
                            "template_code",
                            tpl,
                            "variables",
                            "{\"otp_code\":\"1\",\"expired_minutes\":\"" + bad + "\"}"));
            assertThat(code(r)).as(bad).isEqualTo("VALIDATION_ERROR");
        }

        String noExpiryVar = data.template(
                com.pnh.support.TestDataFactory.unique("OTPN").toUpperCase().replace('-', '_'),
                "SMS",
                "OTP",
                false,
                "otp_code",
                null,
                null,
                null,
                "Ma ${otp_code}",
                "otp_code");
        assertThat(code(send(
                        mvc,
                        c,
                        json(
                                "client_request_id",
                                rid(),
                                "user_id",
                                "u",
                                "channel_type",
                                "SMS",
                                "recipient_target",
                                "+84901234567",
                                "template_code",
                                noExpiryVar,
                                "variables",
                                "{\"otp_code\":\"1\"}"))))
                .isEqualTo("OTP_EXPIRY_REQUIRED");
    }

    @Test
    void noAttemptAfterExpiry() throws Exception {
        clock.setFixed(Instant.parse("2026-11-03T10:00:00Z"));
        ProviderStubs.WIREMOCK.stubFor(post(urlPathEqualTo("/sms/send"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(503).withFixedDelay(700)));
        UUID id = notificationId(send(
                mvc,
                data.client("SEND"),
                json(
                        "client_request_id",
                        rid(),
                        "user_id",
                        "u",
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        "+84901234567",
                        "category",
                        "OTP",
                        "otp_value",
                        "111222",
                        "expires_at",
                        "2026-11-03T10:01:00Z",
                        "content",
                        "{\"body\":\"Code 111222\"}")));
        awaitStatus(tracking, id, NotificationStatus.SENDING);
        clock.setFixed(Instant.parse("2026-11-03T10:02:00Z")); // expires while the first attempt is in flight
        NotificationView v = awaitStatus(tracking, id, NotificationStatus.FAILED, Duration.ofSeconds(15));
        assertThat(v.errorCode()).isEqualTo(ErrorCode.OTP_EXPIRED);
        ProviderStubs.WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/sms/send")));
    }
}
