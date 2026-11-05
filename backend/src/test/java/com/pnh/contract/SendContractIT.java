package com.pnh.contract;

import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** T070 — POST /api/v1/notifications/send against client-api.openapi.yaml. */
class SendContractIT extends StubbedProvidersTestBase {

    private String request(String requestId) {
        return json(
                "client_request_id",
                requestId,
                "user_id",
                "user_99",
                "channel_type",
                "EMAIL",
                "recipient_target",
                "user@example.com",
                "template_code",
                "NEWSLETTER_EMAIL",
                "variables",
                "{\"name\":\"An\"}");
    }

    @Test
    void acceptedAndReplay() throws Exception {
        data.template(
                "NEWSLETTER_EMAIL",
                "EMAIL",
                "STANDARD",
                false,
                null,
                null,
                "Hello ${name}",
                null,
                "<p>Hi ${name}</p>",
                "name");
        ClientCreds c = data.client("SEND,TRACK");
        String id = "c-" + UUID.randomUUID();
        MvcResult accepted = send(mvc, c, request(id));
        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, accepted);
        MvcResult replay = send(mvc, c, request(id));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, replay);
    }

    @Test
    void errorResponses() throws Exception {
        MvcResult unauthorized = mvc.perform(post("/api/v1/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("x")))
                .andReturn();
        assertThat(unauthorized.getResponse().getStatus()).isEqualTo(401);
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, unauthorized);

        MvcResult forbidden = send(mvc, data.client("TRACK"), request("x"));
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, forbidden);

        MvcResult invalid = send(
                mvc,
                data.client("SEND"),
                json(
                        "client_request_id",
                        "x",
                        "user_id",
                        "u",
                        "channel_type",
                        "EMAIL",
                        "recipient_target",
                        "user@example.com",
                        "template_code",
                        "NOPE_NOT_THERE"));
        assertThat(invalid.getResponse().getStatus()).isEqualTo(422);
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, invalid);

        ClientCreds limited = data.client("SEND", 1, true, "ACTIVE");
        send(mvc, limited, request("r1"));
        MvcResult limitedRes = send(mvc, limited, request("r2"));
        assertThat(limitedRes.getResponse().getStatus()).isEqualTo(429);
        assertThat(limitedRes.getResponse().getHeader("Retry-After")).isNotBlank();
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, limitedRes);
    }
}
