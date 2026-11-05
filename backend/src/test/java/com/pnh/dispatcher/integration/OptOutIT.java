package com.pnh.dispatcher.integration;

import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.MailSupport;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory;
import com.pnh.support.TestDataFactory.ClientCreds;
import com.pnh.tracking.NotificationView;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** T126 — opt-out (FR-008): STANDARD non-critical blocked; OTP and critical bypass with OPT_OUT_OVERRIDDEN. */
class OptOutIT extends StubbedProvidersTestBase {

    private void setEmail(ClientCreds c, String user, boolean enabled) throws Exception {
        assertThat(mvc.perform(put("/api/v1/users/" + user + "/preferences")
                                .header("Authorization", c.basic())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"channels\":{\"EMAIL\":" + enabled + "}}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    private UUID sendTemplate(ClientCreds c, String user, String to, String code) throws Exception {
        return notificationId(send(
                mvc,
                c,
                json(
                        "client_request_id",
                        "oo-" + UUID.randomUUID(),
                        "user_id",
                        user,
                        "channel_type",
                        "EMAIL",
                        "recipient_target",
                        to,
                        "template_code",
                        code,
                        "expires_at",
                        "2099-01-01T00:00:00Z",
                        "variables",
                        "{\"otp_code\":\"135792\",\"x\":\"1\"}")));
    }

    private int events(UUID id, String type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pnh_notification_events WHERE notification_id=? AND event_type=?",
                Integer.class,
                id,
                type);
    }

    @Test
    void preferencesDriveDelivery() throws Exception {
        ClientCreds c = data.client("SEND,USER_DELEGATE");
        String user = "user-" + UUID.randomUUID();
        String standard = data.template("EMAIL", "STANDARD", "Hi", null, "<p>${x}</p>", "x");
        String critical = data.template(
                TestDataFactory.unique("CRIT").toUpperCase().replace('-', '_'),
                "EMAIL",
                "STANDARD",
                true,
                null,
                null,
                "Security",
                null,
                "<p>${x}</p>",
                "x");
        String otp = data.template(
                TestDataFactory.unique("OTPO").toUpperCase().replace('-', '_'),
                "EMAIL",
                "OTP",
                false,
                "otp_code",
                null,
                "Code",
                null,
                "<p>${otp_code}</p>",
                "otp_code");

        // no row → enabled
        String to1 = MailSupport.uniqueAddress();
        awaitStatus(tracking, sendTemplate(c, user, to1, standard), NotificationStatus.SENT);

        setEmail(c, user, false);
        String blockedTo = MailSupport.uniqueAddress();
        NotificationView blocked =
                awaitStatus(tracking, sendTemplate(c, user, blockedTo, standard), NotificationStatus.FAILED);
        assertThat(blocked.errorCode()).isEqualTo(ErrorCode.USER_OPTED_OUT);
        UUID direct = notificationId(send(
                mvc,
                c,
                json(
                        "client_request_id",
                        "dd-" + UUID.randomUUID(),
                        "user_id",
                        user,
                        "channel_type",
                        "EMAIL",
                        "recipient_target",
                        blockedTo,
                        "content",
                        "{\"subject\":\"s\",\"body\":\"<p>b</p>\"}")));
        assertThat(awaitStatus(tracking, direct, NotificationStatus.FAILED).errorCode())
                .isEqualTo(ErrorCode.USER_OPTED_OUT);
        assertThat(MailSupport.to(blockedTo)).isEmpty();

        UUID otpId = sendTemplate(c, user, MailSupport.uniqueAddress(), otp);
        NotificationView otpView = awaitStatus(tracking, otpId, NotificationStatus.SENT);
        assertThat(otpView.optOutOverridden()).isTrue();
        assertThat(events(otpId, "OPT_OUT_OVERRIDDEN")).isEqualTo(1);
        UUID critId = sendTemplate(c, user, MailSupport.uniqueAddress(), critical);
        assertThat(awaitStatus(tracking, critId, NotificationStatus.SENT).optOutOverridden())
                .isTrue();

        setEmail(c, user, true);
        awaitStatus(tracking, sendTemplate(c, user, MailSupport.uniqueAddress(), standard), NotificationStatus.SENT);

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor, version FROM pnh_config_audit_log WHERE entity_type='USER_PREFERENCE' "
                        + "AND entity_id=? ORDER BY id DESC LIMIT 1",
                user + ":EMAIL");
        assertThat(audit.get("actor")).isEqualTo(c.clientId() + ":" + user);
        assertThat(audit.get("version")).isEqualTo(1L);
    }
}
