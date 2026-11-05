package com.pnh.shared.integration;

import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.shared.logging.SensitiveDataMaskingConverter;
import com.pnh.support.ConsoleSession;
import com.pnh.support.MailSupport;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MvcResult;

/** T184 — no OTP, secret or full recipient appears in logs during an OTP flow + a config change; security headers set. */
class LogLeakIT extends StubbedProvidersTestBase {

    @Test
    void noSecretsInLogsAndHardenedHeaders() throws Exception {
        String otp = "908172";
        String newSecret = "leak-check-" + UUID.randomUUID();
        String to = MailSupport.uniqueAddress();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Level previous = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.DEBUG);
        root.addAppender(logs);
        try {
            String tpl = data.template(
                    TestDataFactory.unique("LEAK").toUpperCase().replace('-', '_'),
                    "EMAIL",
                    "OTP",
                    false,
                    "otp_code",
                    null,
                    "Code ${otp_code}",
                    null,
                    "<p>${otp_code}</p>",
                    "otp_code");
            UUID id = notificationId(send(
                    mvc,
                    data.client("SEND"),
                    json(
                            "client_request_id",
                            "leak-" + UUID.randomUUID(),
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
                            "{\"otp_code\":\"" + otp + "\"}")));
            awaitStatus(tracking, id, NotificationStatus.SENT);

            ConsoleSession admin = ConsoleSession.login(mvc, data, "ADMIN");
            long smsId = jdbc.queryForObject(
                    "SELECT id FROM pnh_channel_configs WHERE channel_type='SMS' AND is_active", Long.class);
            long version = jdbc.queryForObject("SELECT version FROM pnh_channel_configs WHERE id=?", Long.class, smsId);
            admin.json(
                    put("/console/api/channel-configs/" + smsId),
                    "{\"channel_type\":\"SMS\",\"provider_key\":\"GENERIC_HTTP_SMS\","
                            + "\"is_active\":true,\"settings\":{\"endpoint\":\"http://localhost:1/x\",\"sender\":\"P\"},\"secrets\":{\"api_key\":\""
                            + newSecret + "\"},\"version\":" + version + "}");
        } finally {
            root.detachAppender(logs);
            root.setLevel(previous);
        }
        SensitiveDataMaskingConverter converter = new SensitiveDataMaskingConverter();
        for (ILoggingEvent e : logs.list) {
            if (e.getLoggerName().startsWith("com.icegreen")
                    || e.getLoggerName().startsWith("wiremock")
                    || e.getLoggerName().startsWith("com.github.tomakehurst")) {
                continue; // the test doubles' own transcripts of what they received (the provider legitimately gets the
                // OTP)
            }
            String rendered = converter.convert(e)
                    + (e.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(e.getThrowableProxy()));
            assertThat(rendered)
                    .as(e.getLoggerName())
                    .doesNotContain(otp)
                    .doesNotContain(newSecret)
                    .doesNotContain(to);
        }

        MvcResult health = mvc.perform(get("/actuator/health")).andReturn();
        assertThat(health.getResponse().getHeader("Content-Security-Policy")).contains("default-src 'self'");
        assertThat(health.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(mvc.perform(get("/actuator/env")).andReturn().getResponse().getStatus())
                .isIn(401, 403, 404);
        assertThat(mvc.perform(get("/actuator/beans")).andReturn().getResponse().getStatus())
                .isIn(401, 403, 404);
    }
}
