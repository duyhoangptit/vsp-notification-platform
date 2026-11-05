package com.pnh.coreapi.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.ProviderStubs;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import com.pnh.tracking.NotificationView;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** T073 */
class DirectContentIT extends StubbedProvidersTestBase {

    private static String rid() {
        return "d-" + UUID.randomUUID();
    }

    private static String code(MvcResult r) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(), "$.code");
    }

    @Test
    void smsBodySentVerbatim() throws Exception {
        MvcResult r = send(
                mvc,
                data.client("SEND"),
                json(
                        "client_request_id",
                        rid(),
                        "user_id",
                        "u1",
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        "+84901234567",
                        "content",
                        "{\"body\":\"Xin chao ${not_a_var} & co\"}"));
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        NotificationView v = awaitStatus(tracking, notificationId(r), NotificationStatus.SENT);
        assertThat(v.compiledContent()).isEqualTo("Xin chao ${not_a_var} & co");
        assertThat(v.templateCode()).isNull();
        ProviderStubs.WIREMOCK.verify(postRequestedFor(urlPathEqualTo("/sms/send"))
                .withRequestBody(equalToJson(
                        "{\"to\":\"+84901234567\",\"message\":\"Xin chao ${not_a_var} & co\"}", true, true)));
    }

    @Test
    void modeAndFieldValidation() throws Exception {
        ClientCreds c = data.client("SEND");
        MvcResult both = send(
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
                        "X_Y_Z",
                        "content",
                        "{\"body\":\"b\"}"));
        assertThat(code(both)).isEqualTo("CONTENT_MODE_CONFLICT");
        MvcResult neither = send(
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
                        "+84901234567"));
        assertThat(neither.getResponse().getStatus()).isEqualTo(422);
        assertThat(code(neither)).isEqualTo("CONTENT_MODE_CONFLICT");
        MvcResult noSubject = send(
                mvc,
                c,
                json(
                        "client_request_id",
                        rid(),
                        "user_id",
                        "u",
                        "channel_type",
                        "EMAIL",
                        "recipient_target",
                        "a@b.co",
                        "content",
                        "{\"body\":\"<p>x</p>\"}"));
        assertThat(code(noSubject)).isEqualTo("MISSING_CONTENT_FIELD");
        MvcResult noTitle = send(
                mvc,
                c,
                json(
                        "client_request_id",
                        rid(),
                        "user_id",
                        "u",
                        "channel_type",
                        "PUSH",
                        "recipient_target",
                        "device-token-1234567890",
                        "content",
                        "{\"body\":\"x\"}"));
        assertThat(code(noTitle)).isEqualTo("MISSING_CONTENT_FIELD");
        MvcResult notAllowed = send(
                mvc,
                data.client("SEND", 100, false, "ACTIVE"),
                json(
                        "client_request_id",
                        rid(),
                        "user_id",
                        "u",
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        "+84901234567",
                        "content",
                        "{\"body\":\"x\"}"));
        assertThat(code(notAllowed)).isEqualTo("DIRECT_CONTENT_NOT_ALLOWED");
    }

    @Test
    void emailHtmlIsSanitizedAndRecorded() throws Exception {
        String evil =
                "<p onclick=\\\"x()\\\">Hi<script>alert(1)</script><a href=\\\"javascript:evil()\\\">l</a><iframe></iframe>"
                        + "<b>bold</b><a href=\\\"https://ok.example/x\\\">ok</a><table><tr><td>c</td></tr></table>"
                        + "<img src=\\\"https://img.example/a.png\\\"></p>";
        String to = com.pnh.support.MailSupport.uniqueAddress();
        MvcResult r = send(
                mvc,
                data.client("SEND"),
                "{\"client_request_id\":\"" + rid() + "\",\"user_id\":\"u\","
                        + "\"channel_type\":\"EMAIL\",\"recipient_target\":\"" + to
                        + "\",\"content\":{\"subject\":\"S\",\"body\":\"" + evil + "\"}}");
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        UUID id = notificationId(r);
        awaitStatus(tracking, id, NotificationStatus.SENT);
        String body = com.pnh.support.MailSupport.html(com.pnh.support.MailSupport.awaitOne(to));
        assertThat(body)
                .doesNotContain("<script")
                .doesNotContain("iframe")
                .doesNotContain("onclick")
                .doesNotContain("javascript:");
        assertThat(body)
                .contains("<b>bold</b>")
                .contains("https://ok.example/x")
                .contains("<table>")
                .contains("https://img.example/a.png");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pnh_notification_events WHERE notification_id=? AND event_type='CONTENT_SANITIZED'",
                        Integer.class,
                        id))
                .isEqualTo(1);
    }
}
