package com.pnh.contract;

import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.ConsoleSession;
import com.pnh.support.StubbedProvidersTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T139 — /console/api/notifications and /{id}. */
class NotificationSearchContractIT extends StubbedProvidersTestBase {

    @Test
    void searchAndDetailConform() throws Exception {
        String req = "nc-" + UUID.randomUUID();
        UUID id = notificationId(send(
                mvc,
                data.client("SEND"),
                json(
                        "client_request_id",
                        req,
                        "user_id",
                        "u",
                        "channel_type",
                        "EMAIL",
                        "recipient_target",
                        "a@b.co",
                        "content",
                        "{\"subject\":\"s\",\"body\":\"<p>b</p>\"}")));
        awaitStatus(tracking, id, NotificationStatus.SENT);
        ConsoleSession admin = ConsoleSession.login(mvc, data, "ADMIN");
        ContractSupport.assertConforms(
                ContractSupport.CONSOLE_API, admin.perform(get("/console/api/notifications?client_request_id=" + req)));
        ContractSupport.assertConforms(
                ContractSupport.CONSOLE_API, admin.perform(get("/console/api/notifications/" + id)));
    }
}
