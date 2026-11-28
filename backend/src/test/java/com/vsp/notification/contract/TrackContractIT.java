package com.vsp.notification.contract;

import static com.vsp.notification.support.SendSupport.awaitStatus;
import static com.vsp.notification.support.SendSupport.json;
import static com.vsp.notification.support.SendSupport.notificationId;
import static com.vsp.notification.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.vsp.notification.shared.domain.NotificationStatus;
import com.vsp.notification.support.StubbedProvidersTestBase;
import com.vsp.notification.support.TestDataFactory.ClientCreds;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** T098 */
class TrackContractIT extends StubbedProvidersTestBase {

    @Test
    void trackOwnRequestAnd404ForOthers() throws Exception {
        ClientCreds owner = data.client("SEND,TRACK");
        String req = "tr-" + UUID.randomUUID();
        UUID id = notificationId(send(
                mvc,
                owner,
                json(
                        "client_request_id",
                        req,
                        "user_id",
                        "u",
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        "+84901234567",
                        "content",
                        "{\"body\":\"hi\"}")));
        awaitStatus(tracking, id, NotificationStatus.SENT);
        MvcResult ok = mvc.perform(get("/api/v1/notifications/track/" + req).header("Authorization", owner.basic()))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString())
                .contains("\"status\":\"SENT\"")
                .contains(id.toString());
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, ok);

        MvcResult other = mvc.perform(get("/api/v1/notifications/track/" + req)
                        .header("Authorization", data.client("TRACK").basic()))
                .andReturn();
        assertThat(other.getResponse().getStatus()).isEqualTo(404);
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, other);
    }
}
