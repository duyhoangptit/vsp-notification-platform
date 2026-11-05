package com.pnh.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.pnh.support.ConsoleSession;
import com.pnh.support.MailSupport;
import com.pnh.support.StubbedProvidersTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** T147 — /console/api/alerts/* (ADMIN only). */
class AlertConsoleContractIT extends StubbedProvidersTestBase {

    private static MvcResult ok(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(status);
        ContractSupport.assertConforms(ContractSupport.CONSOLE_API, r);
        return r;
    }

    @Test
    void recipientsRulesEventsAndTest() throws Exception {
        ConsoleSession admin = ConsoleSession.login(mvc, data, "ADMIN");
        String email = MailSupport.uniqueAddress().toUpperCase();
        MvcResult created = ok(
                admin.json(
                        post("/console/api/alerts/recipients"),
                        "{\"email\":\"" + email + "\",\"display_name\":\"Ops\"}"),
                201);
        int id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        assertThat(created.getResponse().getContentAsString()).contains(email.toLowerCase());
        ok(admin.json(post("/console/api/alerts/recipients"), "{\"email\":\"" + email.toLowerCase() + "\"}"), 409);
        // intentionally invalid request: assert the response only
        assertThat(admin.json(post("/console/api/alerts/recipients"), "{\"email\":\"not-an-email\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(422);
        ok(admin.perform(get("/console/api/alerts/recipients")), 200);
        ok(admin.json(put("/console/api/alerts/recipients/" + id), "{\"is_active\":false,\"version\":0}"), 200);
        ok(admin.json(put("/console/api/alerts/recipients/" + id), "{\"is_active\":true,\"version\":0}"), 409);
        ok(admin.perform(post("/console/api/alerts/recipients/test")), 200);
        assertThat(admin.perform(delete("/console/api/alerts/recipients/" + id + "?version=1"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);

        MvcResult rules = ok(admin.perform(get("/console/api/alerts/rules")), 200);
        List<String> codes = JsonPath.read(rules.getResponse().getContentAsString(), "$[*].rule_code");
        assertThat(codes)
                .contains(
                        "STUCK_DELIVERY_SPIKE",
                        "PROVIDER_UNAVAILABLE",
                        "CHANNEL_FAILURE_RATE",
                        "INGEST_REJECTED_SPIKE");
        List<Integer> versions = JsonPath.read(
                rules.getResponse().getContentAsString(), "$[?(@.rule_code=='INGEST_REJECTED_SPIKE')].version");
        int version = versions.get(0);
        ok(
                admin.json(
                        put("/console/api/alerts/rules/INGEST_REJECTED_SPIKE"),
                        "{\"enabled\":true,\"threshold\":25,\"window_minutes\":10,"
                                + "\"min_sample\":null,\"cooldown_minutes\":30,\"version\":" + version + "}"),
                200);
        assertThat(admin.json(
                                put("/console/api/alerts/rules/INGEST_REJECTED_SPIKE"),
                                "{\"enabled\":true,\"threshold\":25,"
                                        + "\"window_minutes\":0,\"cooldown_minutes\":30,\"version\":" + (version + 1)
                                        + "}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(422);
        ok(
                admin.json(
                        put("/console/api/alerts/rules/INGEST_REJECTED_SPIKE"),
                        "{\"enabled\":true,\"threshold\":20,\"window_minutes\":10,"
                                + "\"cooldown_minutes\":30,\"version\":" + version + "}"),
                409);
        ok(
                admin.json(
                        put("/console/api/alerts/rules/INGEST_REJECTED_SPIKE"),
                        "{\"enabled\":true,\"threshold\":20,\"window_minutes\":10,"
                                + "\"cooldown_minutes\":30,\"version\":" + (version + 1) + "}"),
                200);
        ok(admin.perform(get("/console/api/alerts/events?page=0&size=10")), 200);

        ConsoleSession support = ConsoleSession.login(mvc, data, "SUPPORT_VIEWER");
        assertThat(support.perform(get("/console/api/alerts/rules"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pnh_config_audit_log WHERE entity_type IN ('ALERT_RECIPIENT','ALERT_RULE') "
                                + "AND actor = ?",
                        Integer.class,
                        admin.username()))
                .isGreaterThanOrEqualTo(4);
    }
}
