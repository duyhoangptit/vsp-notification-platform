package com.pnh.tracking.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import com.pnh.contract.ContractSupport;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.ConsoleSession;
import com.pnh.support.ProviderStubs;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** T139 + T140 — Delivery Audit search and detail. */
class NotificationSearchIT extends StubbedProvidersTestBase {

    private UUID sms(ClientCreds c, String requestId, String user, String phone) throws Exception {
        return notificationId(send(
                mvc,
                c,
                json(
                        "client_request_id",
                        requestId,
                        "user_id",
                        user,
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        phone,
                        "content",
                        "{\"body\":\"audit\"}")));
    }

    private static List<String> ids(MvcResult r) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(), "$.items[*].notification_id");
    }

    @Test
    void filtersPaginationMaskingAndDetailTimeline() throws Exception {
        ClientCreds c = data.client("SEND");
        String user = "audit-" + UUID.randomUUID();
        UUID first = sms(c, "a1-" + UUID.randomUUID(), user, "+84901000111");
        UUID second = sms(c, "a2-" + UUID.randomUUID(), user, "+84901000222");
        awaitStatus(tracking, first, NotificationStatus.SENT);
        awaitStatus(tracking, second, NotificationStatus.SENT);
        ProviderStubs.WIREMOCK.stubFor(post(urlPathEqualTo("/sms/send"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"gateway down\"}")));
        String failedReq = "a3-" + UUID.randomUUID();
        UUID failed = sms(c, failedReq, user, "+84901000333");
        awaitStatus(tracking, failed, NotificationStatus.FAILED);

        ConsoleSession support = ConsoleSession.login(mvc, data, "SUPPORT_VIEWER");
        MvcResult byUser = support.perform(get("/console/api/notifications?user_id=" + user));
        assertThat(byUser.getResponse().getStatus()).isEqualTo(200);
        ContractSupport.assertConforms(ContractSupport.CONSOLE_API, byUser);
        assertThat(ids(byUser)).containsExactly(failed.toString(), second.toString(), first.toString()); // newest first
        assertThat(byUser.getResponse().getContentAsString())
                .contains("+84*****333")
                .doesNotContain("+84901000333");

        assertThat(ids(support.perform(get("/console/api/notifications?user_id=" + user + "&status=FAILED"))))
                .containsExactly(failed.toString());
        assertThat(ids(support.perform(get("/console/api/notifications").param("recipient_target", "+84901000222"))))
                .containsExactly(second.toString());
        assertThat(ids(support.perform(get("/console/api/notifications?client_request_id=" + failedReq))))
                .containsExactly(failed.toString());
        assertThat(ids(support.perform(get(
                        "/console/api/notifications?client_id=" + c.clientId() + "&channel_type=SMS&user_id=" + user))))
                .hasSize(3);
        MvcResult page = support.perform(get("/console/api/notifications?user_id=" + user + "&page=1&size=2"));
        assertThat(ids(page)).containsExactly(first.toString());
        assertThat((Integer) JsonPath.read(page.getResponse().getContentAsString(), "$.total"))
                .isEqualTo(3);

        MvcResult tooWide =
                support.perform(get("/console/api/notifications?from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z"));
        assertThat(tooWide.getResponse().getStatus()).isEqualTo(422);

        MvcResult detail = support.perform(get("/console/api/notifications/" + failed));
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        ContractSupport.assertConforms(ContractSupport.CONSOLE_API, detail);
        String d = detail.getResponse().getContentAsString();
        assertThat(d).contains("\"recipient_target\":\"+84901000333\"").contains("\"content\":\"audit\"");
        List<String> types = JsonPath.read(d, "$.timeline[*].event_type");
        assertThat(types).contains("ATTEMPT_FAILED", "STATUS_CHANGED");
        List<Integer> attempts = JsonPath.read(d, "$.timeline[?(@.event_type == 'ATTEMPT_FAILED')].attempt");
        assertThat(attempts).containsExactly(1, 2, 3, 4);
        assertThat(d).contains("PROVIDER_UNAVAILABLE");

        assertThat(support.perform(get("/console/api/notifications/" + UUID.randomUUID()))
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        ConsoleSession editor = ConsoleSession.login(mvc, data, "TEMPLATE_EDITOR");
        assertThat(editor.perform(get("/console/api/notifications?user_id=" + user))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }
}
