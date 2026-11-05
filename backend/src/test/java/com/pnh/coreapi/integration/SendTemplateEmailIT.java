package com.pnh.coreapi.integration;

import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.MailSupport;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import com.pnh.tracking.NotificationView;
import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** T071 */
class SendTemplateEmailIT extends StubbedProvidersTestBase {

    @Test
    void rendersEscapesAndDeliversExactlyOnce() throws Exception {
        String to = MailSupport.uniqueAddress();
        String code = data.template("EMAIL", "STANDARD", "Hello ${name}", null, "<p>Hi <b>${name}</b></p>", "name");
        ClientCreds c = data.client("SEND,TRACK");
        MvcResult r = send(
                mvc,
                c,
                json(
                        "client_request_id",
                        "t-" + UUID.randomUUID(),
                        "user_id",
                        "u1",
                        "channel_type",
                        "EMAIL",
                        "recipient_target",
                        "User-" + to.substring(2, 10) + "@Example.com",
                        "template_code",
                        code,
                        "variables",
                        "{\"name\":\"<i>An</i>\"}"));
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        UUID id = notificationId(r);
        NotificationView v = awaitStatus(tracking, id, NotificationStatus.SENT);
        assertThat(v.templateVersion()).isZero();
        String expected = "user-" + to.substring(2, 10) + "@example.com";
        MimeMessage mail = MailSupport.awaitOne(expected);
        assertThat(MailSupport.to(expected)).hasSize(1);
        assertThat(mail.getSubject()).isEqualTo("Hello <i>An</i>");
        assertThat(MailSupport.html(mail)).contains("&lt;i&gt;An&lt;/i&gt;").doesNotContain("<i>An</i>");
    }

    @Test
    void contentFrozenAtAcceptanceEvenIfTemplateEditedAndDeactivated() throws Exception {
        String frozenTo = MailSupport.uniqueAddress();
        String code = data.template("EMAIL", "STANDARD", "Old ${name}", null, "<p>Old body ${name}</p>", "name");
        ClientCreds c = data.client("SEND");
        MvcResult r = send(
                mvc,
                c,
                json(
                        "client_request_id",
                        "f-" + UUID.randomUUID(),
                        "user_id",
                        "u1",
                        "channel_type",
                        "EMAIL",
                        "recipient_target",
                        frozenTo,
                        "template_code",
                        code,
                        "variables",
                        "{\"name\":\"An\"}"));
        UUID id = notificationId(r);
        jdbc.update(
                "UPDATE pnh_templates SET body_template = '<p>New ${name}</p>', subject_template = 'New', status = 'INACTIVE', "
                        + "version = version + 1 WHERE template_code = ?",
                code);
        NotificationView v = awaitStatus(tracking, id, NotificationStatus.SENT);
        assertThat(v.templateVersion()).isZero();
        MimeMessage mail = MailSupport.awaitOne(frozenTo);
        assertThat(mail.getSubject()).isEqualTo("Old An");
        assertThat(MailSupport.html(mail)).contains("Old body An");
    }

    @Test
    void attributePlaceholderLinkDelivered() throws Exception {
        String linkTo = MailSupport.uniqueAddress();
        String code = data.template(
                "EMAIL", "STANDARD", "Reset", null, "<a href=\"${reset_link}\">Đặt lại</a>", "reset_link");
        MvcResult r = send(
                mvc,
                data.client("SEND"),
                json(
                        "client_request_id",
                        "a-" + UUID.randomUUID(),
                        "user_id",
                        "u1",
                        "channel_type",
                        "EMAIL",
                        "recipient_target",
                        linkTo,
                        "template_code",
                        code,
                        "variables",
                        "{\"reset_link\":\"https://example.com/reset?t=abc\"}"));
        awaitStatus(tracking, notificationId(r), NotificationStatus.SENT);
        assertThat(MailSupport.html(MailSupport.awaitOne(linkTo))).contains("href=\"https://example.com/reset?t=abc\"");
    }
}
