package com.pnh.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.pnh.support.ConsoleSession;
import com.pnh.support.ProviderStubs;
import com.pnh.support.StubbedProvidersTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** T166 — /console/api/channel-configs* and /console/api/config-change-log; secrets never returned. */
class ChannelConfigContractIT extends StubbedProvidersTestBase {

    private static MvcResult ok(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(status);
        ContractSupport.assertConforms(ContractSupport.CONSOLE_API, r);
        return r;
    }

    @Test
    void configsAndChangeLog() throws Exception {
        jdbc.update("DELETE FROM pnh_channel_configs WHERE provider_key = 'ANDROID_SMS_GATEWAY'");
        ConsoleSession admin = ConsoleSession.login(mvc, data, "ADMIN");
        MvcResult list = ok(admin.perform(get("/console/api/channel-configs")), 200);
        String body = list.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain(ProviderStubs.SMS_API_KEY)
                .doesNotContain(ProviderStubs.SMS_CALLBACK_SECRET)
                .doesNotContain("PRIVATE KEY")
                .contains("\"has_value\":true")
                .contains("\"masked\":\"********\"");

        MvcResult created = ok(
                admin.json(
                        post("/console/api/channel-configs"),
                        "{\"channel_type\":\"SMS\",\"provider_key\":"
                                + "\"ANDROID_SMS_GATEWAY\",\"is_active\":false,\"settings\":{\"mode\":\"cloud\"},"
                                + "\"secrets\":{\"username\":\"u\",\"password\":\"top-secret-pw\"}}"),
                201);
        assertThat(created.getResponse().getContentAsString()).doesNotContain("top-secret-pw");
        int id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        ok(
                admin.json(
                        put("/console/api/channel-configs/" + id),
                        "{\"channel_type\":\"SMS\",\"provider_key\":\"ANDROID_SMS_GATEWAY\","
                                + "\"is_active\":false,\"settings\":{\"mode\":\"cloud\"},\"version\":0}"),
                200);
        ok(
                admin.json(
                        put("/console/api/channel-configs/" + id),
                        "{\"channel_type\":\"SMS\",\"provider_key\":\"ANDROID_SMS_GATEWAY\","
                                + "\"is_active\":false,\"settings\":{\"mode\":\"cloud\"},\"version\":0}"),
                409);
        ok(
                admin.json(
                        post("/console/api/channel-configs"),
                        "{\"channel_type\":\"SMS\",\"provider_key\":\"GENERIC_HTTP_SMS\","
                                + "\"settings\":{\"endpoint\":\"not a url\"}}"),
                422);

        Long smtpId = jdbc.queryForObject(
                "SELECT id FROM pnh_channel_configs WHERE channel_type='EMAIL' AND is_active", Long.class);
        ok(admin.perform(post("/console/api/channel-configs/" + smtpId + "/test")), 200);

        MvcResult log =
                ok(admin.perform(get("/console/api/config-change-log?entity_type=CHANNEL_CONFIG&page=0&size=20")), 200);
        List<String> types = JsonPath.read(log.getResponse().getContentAsString(), "$.items[*].entity_type");
        assertThat(types).isNotEmpty().allMatch("CHANNEL_CONFIG"::equals);
        assertThat(log.getResponse().getContentAsString())
                .contains("***changed***")
                .doesNotContain("top-secret-pw");

        ConsoleSession editor = ConsoleSession.login(mvc, data, "TEMPLATE_EDITOR");
        assertThat(editor.perform(get("/console/api/channel-configs"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(editor.perform(get("/console/api/config-change-log"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }
}
