package com.pnh.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.pnh.support.InboxSupport;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** T156 */
class InboxContractIT extends StubbedProvidersTestBase {

    @Test
    void inboxEndpointsConform() throws Exception {
        ClientCreds c = data.client("SEND,USER_DELEGATE");
        String user = "inbox-" + UUID.randomUUID();
        UUID id = InboxSupport.push(mvc, c, tracking, jdbc, user, "Hello");

        MvcResult list = mvc.perform(
                        get("/api/v1/users/" + user + "/inbox?page=0&size=10").header("Authorization", c.basic()))
                .andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, list);

        MvcResult read = mvc.perform(patch("/api/v1/notifications/" + id + "/read")
                        .header("Authorization", c.basic())
                        .header("X-User-Id", user))
                .andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, read);

        MvcResult notFound = mvc.perform(patch("/api/v1/notifications/" + UUID.randomUUID() + "/read")
                        .header("Authorization", c.basic())
                        .header("X-User-Id", user))
                .andReturn();
        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, notFound);

        MvcResult bulk = mvc.perform(post("/api/v1/notifications/bulk-read")
                        .header("Authorization", c.basic())
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notification_ids\":[\"" + id + "\",\"" + UUID.randomUUID() + "\"]}"))
                .andReturn();
        assertThat(bulk.getResponse().getStatus()).isEqualTo(200);
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, bulk);

        String tooMany = IntStream.range(0, 501)
                .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(","));
        MvcResult limit = mvc.perform(post("/api/v1/notifications/bulk-read")
                        .header("Authorization", c.basic())
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notification_ids\":[" + tooMany + "]}"))
                .andReturn();
        assertThat(limit.getResponse().getStatus()).isEqualTo(422);
        assertThat(limit.getResponse().getContentAsString()).contains("BULK_LIMIT_EXCEEDED");
    }
}
