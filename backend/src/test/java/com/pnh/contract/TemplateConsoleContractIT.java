package com.pnh.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.pnh.support.ConsoleSession;
import com.pnh.support.IntegrationTestBase;
import com.pnh.support.TestDataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** T130 — /console/api/templates* against console-api.openapi.yaml. */
class TemplateConsoleContractIT extends IntegrationTestBase {

    static String input(String code, String body) {
        return "{\"template_code\":\"" + code
                + "\",\"template_name\":\"Welcome\",\"channel_type\":\"EMAIL\",\"category\":\"STANDARD\","
                + "\"subject_template\":\"Hi ${name}\",\"body_template\":\"" + body + "\"}";
    }

    private static void ok(MvcResult r, int status) {
        assertThat(r.getResponse().getStatus())
                .as(() -> {
                    try {
                        return r.getResponse().getContentAsString();
                    } catch (Exception e) {
                        return "";
                    }
                })
                .isEqualTo(status);
        ContractSupport.assertConforms(ContractSupport.CONSOLE_API, r);
    }

    @Test
    void fullLifecycle() throws Exception {
        ConsoleSession s = ConsoleSession.login(mvc, data, "TEMPLATE_EDITOR");
        String code = TestDataFactory.unique("WELCOME").toUpperCase().replace('-', '_');
        MvcResult created = s.json(post("/console/api/templates"), input(code, "<p>Hello ${name}, ${shop}</p>"));
        ok(created, 201);
        assertThat(created.getResponse().getContentAsString())
                .contains("\"required_variables\":[\"name\",\"shop\"]")
                .contains("\"version\":0")
                .contains("\"created_by\":\"" + s.username() + "\"");

        ok(s.json(post("/console/api/templates"), input(code, "<p>x</p>")), 409);
        ok(s.json(post("/console/api/templates"), input(code + "_B", "<p>${T(java.lang.Runtime)}</p>")), 422);

        ok(
                s.perform(get("/console/api/templates?q=" + code.substring(0, 10)
                        + "&channel_type=EMAIL&status=ACTIVE&page=0&size=10")),
                200);
        ok(s.perform(get("/console/api/templates/" + code)), 200);
        ok(s.perform(get("/console/api/templates/NOPE_NOPE_NOPE")), 404);

        String base = input(code, "<p>Hi ${name}</p>");
        String update = base.substring(0, base.length() - 1) + ",\"version\":0}";
        MvcResult updated = s.json(put("/console/api/templates/" + code), update);
        ok(updated, 200);
        assertThat((Integer) JsonPath.read(updated.getResponse().getContentAsString(), "$.version"))
                .isEqualTo(1);
        MvcResult conflict = s.json(put("/console/api/templates/" + code), update);
        ok(conflict, 409);
        assertThat(conflict.getResponse().getContentAsString())
                .contains("VERSION_CONFLICT")
                .contains("\"current\"");

        MvcResult deactivated = s.perform(delete("/console/api/templates/" + code + "?version=1"));
        ok(deactivated, 200);
        assertThat(deactivated.getResponse().getContentAsString()).contains("\"status\":\"INACTIVE\"");
        ok(s.perform(post("/console/api/templates/" + code + "/activate?version=2")), 200);

        MvcResult preview = s.json(
                post("/console/api/templates/preview"),
                "{\"channel_type\":\"SMS\",\"body_template\":\"Xin chao ${name}\",\"variables\":{}}");
        ok(preview, 200);
        assertThat(preview.getResponse().getContentAsString())
                .contains("\"missing_variables\":[\"name\"]")
                .contains("\"sms_segments\":1");
    }

    @Test
    void supportViewerForbidden() throws Exception {
        ConsoleSession s = ConsoleSession.login(mvc, data, "SUPPORT_VIEWER");
        MvcResult r = s.perform(get("/console/api/templates"));
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("FORBIDDEN");
    }
}
