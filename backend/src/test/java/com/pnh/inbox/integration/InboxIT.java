package com.pnh.inbox.integration;

import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.InboxSupport;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** T157 — inbox (US6, FR-018/019). */
class InboxIT extends StubbedProvidersTestBase {

    private MvcResult list(ClientCreds c, String user, String query) throws Exception {
        return mvc.perform(get("/api/v1/users/" + user + "/inbox" + query).header("Authorization", c.basic()))
                .andReturn();
    }

    private MvcResult markRead(ClientCreds c, String user, UUID id) throws Exception {
        return mvc.perform(patch("/api/v1/notifications/" + id + "/read")
                        .header("Authorization", c.basic())
                        .header("X-User-Id", user))
                .andReturn();
    }

    @Test
    void onlyPushStandardListedNewestFirstWithReadState() throws Exception {
        ClientCreds c = data.client("SEND,USER_DELEGATE");
        String user = "inbox-" + UUID.randomUUID();
        UUID first = InboxSupport.push(mvc, c, tracking, jdbc, user, "First");
        UUID second = InboxSupport.push(mvc, c, tracking, jdbc, user, "Second");
        UUID email = notificationId(send(
                mvc,
                c,
                json(
                        "client_request_id",
                        "e-" + UUID.randomUUID(),
                        "user_id",
                        user,
                        "channel_type",
                        "EMAIL",
                        "recipient_target",
                        "a@b.co",
                        "content",
                        "{\"subject\":\"s\",\"body\":\"<p>x</p>\"}")));
        UUID otpPush = notificationId(send(
                mvc,
                c,
                json(
                        "client_request_id",
                        "o-" + UUID.randomUUID(),
                        "user_id",
                        user,
                        "channel_type",
                        "PUSH",
                        "recipient_target",
                        "device-token-otp-123",
                        "category",
                        "OTP",
                        "otp_value",
                        "5555",
                        "expires_at",
                        "2099-01-01T00:00:00Z",
                        "content",
                        "{\"title\":\"Code\",\"body\":\"5555\"}")));
        awaitStatus(tracking, email, NotificationStatus.SENT);
        awaitStatus(tracking, otpPush, NotificationStatus.SENT);

        MvcResult page = list(c, user, "");
        List<String> ids = JsonPath.read(page.getResponse().getContentAsString(), "$.items[*].notification_id");
        assertThat(ids).containsExactly(second.toString(), first.toString());
        assertThat((Integer) JsonPath.read(page.getResponse().getContentAsString(), "$.unread_count"))
                .isEqualTo(2);
        assertThat(page.getResponse().getContentAsString())
                .contains("\"title\":\"Second\"")
                .contains("\"order\":\"42\"");

        MvcResult read = markRead(c, user, first);
        String readAt = JsonPath.read(read.getResponse().getContentAsString(), "$.read_at");
        clock.setOffset(Duration.ofMinutes(5));
        MvcResult again = markRead(c, user, first);
        assertThat((String) JsonPath.read(again.getResponse().getContentAsString(), "$.read_at"))
                .isEqualTo(readAt);
        assertThat(Instant.parse(readAt)).isNotNull();
        clock.reset();

        MvcResult unreadOnly = list(c, user, "?unread_only=true");
        assertThat((List<String>)
                        JsonPath.read(unreadOnly.getResponse().getContentAsString(), "$.items[*].notification_id"))
                .containsExactly(second.toString());
        assertThat((Integer) JsonPath.read(unreadOnly.getResponse().getContentAsString(), "$.unread_count"))
                .isEqualTo(1);

        UUID missing = UUID.randomUUID();
        MvcResult bulk = mvc.perform(post("/api/v1/notifications/bulk-read")
                        .header("Authorization", c.basic())
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notification_ids\":[\"" + second + "\",\"" + first + "\",\"" + missing + "\",\""
                                + email + "\"]}"))
                .andReturn();
        assertThat((Integer) JsonPath.read(bulk.getResponse().getContentAsString(), "$.updated_count"))
                .isEqualTo(1);
        assertThat((List<String>) JsonPath.read(bulk.getResponse().getContentAsString(), "$.not_found_ids"))
                .containsExactlyInAnyOrder(missing.toString(), email.toString());

        assertThat(markRead(c, "someone-else", second).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(markRead(c, user, email).getResponse().getStatus()).isEqualTo(404);
        assertThat(markRead(c, user, otpPush).getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(patch("/api/v1/notifications/" + second + "/read")
                                .header("Authorization", c.basic()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(list(data.client("SEND"), user, "").getResponse().getStatus())
                .isEqualTo(403);
    }
}
