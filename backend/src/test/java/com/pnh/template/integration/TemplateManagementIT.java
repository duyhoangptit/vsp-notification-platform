package com.pnh.template.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.pnh.support.ConsoleSession;
import com.pnh.support.IntegrationTestBase;
import com.pnh.support.SecondaryContext;
import com.pnh.support.TestDataFactory;
import com.pnh.template.TemplateApi;
import com.pnh.template.TemplateInfo;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/** T131 */
class TemplateManagementIT extends IntegrationTestBase {

    static ConfigurableApplicationContext replica;

    @BeforeAll
    static void start() {
        replica = SecondaryContext.start();
    }

    @AfterAll
    static void stop() {
        replica.close();
    }

    private static String body(String code, String subject, String html, String extra) {
        return "{\"template_code\":\"" + code
                + "\",\"template_name\":\"T\",\"channel_type\":\"EMAIL\",\"category\":\"STANDARD\","
                + "\"subject_template\":\"" + subject + "\",\"body_template\":\"" + html + "\"" + extra + "}";
    }

    @Test
    void lifecycleAuditAndCrossReplicaEviction() throws Exception {
        ConsoleSession admin = ConsoleSession.login(mvc, data, "ADMIN");
        String code = TestDataFactory.unique("MGMT").toUpperCase().replace('-', '_');
        assertThat(admin.json(post("/console/api/templates"), body(code, "S1", "<p>${a}</p><script>x()</script>", ""))
                        .getResponse()
                        .getStatus())
                .isEqualTo(201);
        TemplateApi remote = replica.getBean(TemplateApi.class);
        assertThat(remote.find(code).map(TemplateInfo::subjectTemplate)).contains("S1"); // now cached on the replica
        assertThat(remote.find(code).orElseThrow().bodyTemplate()).doesNotContain("script"); // sanitized on save

        assertThat(admin.json(
                                put("/console/api/templates/" + code),
                                body(code, "S2", "<p>${a} ${b}</p>", ",\"version\":0"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        await().atMost(Duration.ofSeconds(1))
                .until(() -> remote.find(code)
                        .map(TemplateInfo::subjectTemplate)
                        .orElse("")
                        .equals("S2"));
        assertThat(remote.find(code).orElseThrow().requiredVariables()).containsExactly("a", "b");

        assertThat(admin.perform(delete("/console/api/templates/" + code + "?version=1"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM pnh_templates WHERE template_code=?", String.class, code))
                .isEqualTo("INACTIVE");
        assertThat(admin.perform(post("/console/api/templates/" + code + "/activate?version=2"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        List<String> actions = jdbc.queryForList(
                "SELECT action FROM pnh_config_audit_log WHERE entity_type='TEMPLATE' AND entity_id=? " + "ORDER BY id",
                String.class,
                code);
        assertThat(actions).containsExactly("CREATE", "UPDATE", "DEACTIVATE", "UPDATE");
        String fields = jdbc.queryForObject(
                "SELECT changed_fields::text FROM pnh_config_audit_log WHERE entity_type='TEMPLATE' "
                        + "AND entity_id=? AND action='UPDATE' ORDER BY id LIMIT 1",
                String.class,
                code);
        assertThat(fields).contains("subject_template").contains("S1").contains("S2");
    }

    @Test
    void validationRules() throws Exception {
        ConsoleSession editor = ConsoleSession.login(mvc, data, "TEMPLATE_EDITOR");
        String code = TestDataFactory.unique("VAL").toUpperCase().replace('-', '_');
        String otpNoVar = "{\"template_code\":\"" + code
                + "\",\"template_name\":\"T\",\"channel_type\":\"SMS\",\"category\":\"OTP\","
                + "\"otp_variable\":\"otp_code\",\"body_template\":\"Hi there\"}";
        assertThat(editor.json(post("/console/api/templates"), otpNoVar)
                        .getResponse()
                        .getStatus())
                .isEqualTo(422);
        String styleAttr = body(code, "S", "<p style=\\\"color:${c}\\\">x</p>", "");
        assertThat(editor.json(post("/console/api/templates"), styleAttr)
                        .getResponse()
                        .getContentAsString())
                .contains("INVALID_PLACEHOLDER");
        String noSubject = "{\"template_code\":\"" + code
                + "\",\"template_name\":\"T\",\"channel_type\":\"EMAIL\",\"category\":\"STANDARD\","
                + "\"body_template\":\"<p>x</p>\"}";
        assertThat(editor.json(post("/console/api/templates"), noSubject)
                        .getResponse()
                        .getStatus())
                .isEqualTo(422);
        String link = body(code, "S", "<a href=\\\"${reset_link}\\\">Reset</a>", "");
        assertThat(editor.json(post("/console/api/templates"), link)
                        .getResponse()
                        .getStatus())
                .isEqualTo(201);
        assertThat(jdbc.queryForObject(
                        "SELECT body_template FROM pnh_templates WHERE template_code=?", String.class, code))
                .contains("href=\"${reset_link}\"");
    }
}
