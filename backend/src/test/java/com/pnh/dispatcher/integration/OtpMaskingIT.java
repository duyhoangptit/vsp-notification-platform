package com.pnh.dispatcher.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.shared.logging.SensitiveDataMaskingConverter;
import com.pnh.support.MailSupport;
import com.pnh.support.ProviderStubs;
import com.pnh.support.ResultMessages;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory;
import com.pnh.support.TestDataFactory.ClientCreds;
import com.pnh.tracking.NotificationView;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** T111 — the OTP never appears in stored content, track, results or logs; only the provider gets it. */
class OtpMaskingIT extends StubbedProvidersTestBase {

    @Test
    void templateAndDirectOtpMaskedEverywhere() throws Exception {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        try {
            ProviderStubs.WIREMOCK.stubFor(post(urlPathEqualTo("/sms/send"))
                    .atPriority(1)
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"message_id\":\"m\"}")
                            .withFixedDelay(600)));
            ClientCreds c = data.client("SEND,TRACK");
            String to = MailSupport.uniqueAddress();
            String tpl = data.template(
                    TestDataFactory.unique("OTPE").toUpperCase().replace('-', '_'),
                    "EMAIL",
                    "OTP",
                    false,
                    "otp_code",
                    null,
                    "Code ${otp_code}",
                    null,
                    "<p>Your code: <b>${otp_code}</b></p>",
                    "otp_code");
            String reqA = "ma-" + UUID.randomUUID();
            UUID a = notificationId(send(
                    mvc,
                    c,
                    json(
                            "client_request_id",
                            reqA,
                            "user_id",
                            "u",
                            "channel_type",
                            "EMAIL",
                            "recipient_target",
                            to,
                            "template_code",
                            tpl,
                            "expires_at",
                            "2099-01-01T00:00:00Z",
                            "variables",
                            "{\"otp_code\":\"482913\"}")));
            // immediately after the 202 — before any dispatch could have happened — the stored content is already
            // masked
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT compiled_content, subject, payload_encrypted FROM pnh_notifications_history WHERE notification_id=?",
                    a);
            assertThat((String) row.get("compiled_content")).contains("******").doesNotContain("482913");
            assertThat((String) row.get("subject")).isEqualTo("Code ******");
            assertThat(row.get("payload_encrypted")).isNotNull();

            UUID b = notificationId(send(
                    mvc,
                    c,
                    json(
                            "client_request_id",
                            "mb-" + UUID.randomUUID(),
                            "user_id",
                            "u",
                            "channel_type",
                            "SMS",
                            "recipient_target",
                            "+84901234567",
                            "category",
                            "OTP",
                            "otp_value",
                            "777333",
                            "expires_at",
                            "2099-01-01T00:00:00Z",
                            "content",
                            "{\"body\":\"Ma 777333 hieu luc 5 phut\"}")));
            NotificationView sending = awaitStatus(tracking, b, NotificationStatus.SENDING);
            assertThat(sending.payloadEncrypted()).isNotNull();
            assertThat(sending.compiledContent()).isEqualTo("Ma ****** hieu luc 5 phut");

            NotificationView sentA = awaitStatus(tracking, a, NotificationStatus.SENT);
            NotificationView sentB = awaitStatus(tracking, b, NotificationStatus.SENT);
            assertThat(sentA.payloadEncrypted()).isNull();
            assertThat(sentB.payloadEncrypted()).isNull();
            assertThat(MailSupport.html(MailSupport.awaitOne(to))).contains("482913"); // provider got the real code

            String track = mvc.perform(
                            get("/api/v1/notifications/track/" + reqA).header("Authorization", c.basic()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(track).doesNotContain("482913");
            ResultMessages.await(a, 1, Duration.ofSeconds(20))
                    .forEach(r -> assertThat(r.value()).doesNotContain("482913"));
            ResultMessages.await(b, 1, Duration.ofSeconds(20))
                    .forEach(r -> assertThat(r.value()).doesNotContain("777333"));
            assertThat(sentB.compiledContent()).doesNotContain("777333");

            SensitiveDataMaskingConverter converter = new SensitiveDataMaskingConverter();
            for (ILoggingEvent e : logs.list) {
                assertThat(converter.convert(e)).doesNotContain("482913").doesNotContain("777333");
                assertThat(e.getFormattedMessage()).doesNotContain("482913").doesNotContain("777333");
            }
        } finally {
            root.detachAppender(logs);
        }
    }
}
