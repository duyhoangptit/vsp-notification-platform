package com.pnh.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** T125 */
class PreferencesContractIT extends StubbedProvidersTestBase {

    @Test
    void getAndPutConformToContract() throws Exception {
        ClientCreds c = data.client("USER_DELEGATE");
        String user = "pref-" + UUID.randomUUID();
        MvcResult initial = mvc.perform(
                        get("/api/v1/users/" + user + "/preferences").header("Authorization", c.basic()))
                .andReturn();
        assertThat(initial.getResponse().getStatus()).isEqualTo(200);
        assertThat(initial.getResponse().getContentAsString())
                .contains("\"EMAIL\":true")
                .contains("\"SMS\":true");
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, initial);

        MvcResult updated = mvc.perform(put("/api/v1/users/" + user + "/preferences")
                        .header("Authorization", c.basic())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channels\":{\"EMAIL\":false}}"))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(updated.getResponse().getContentAsString())
                .contains("\"EMAIL\":false")
                .contains("\"SMS\":true");
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, updated);

        MvcResult invalid = mvc.perform(put("/api/v1/users/" + user + "/preferences")
                        .header("Authorization", c.basic())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channels\":{\"FAX\":false}}"))
                .andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(422);
        ContractSupport.assertConforms(ContractSupport.CLIENT_API, invalid);

        assertThat(mvc.perform(get("/api/v1/users/" + user + "/preferences")
                                .header("Authorization", data.client("SEND").basic()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }
}
