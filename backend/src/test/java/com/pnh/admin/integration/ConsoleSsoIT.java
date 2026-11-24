package com.pnh.admin.integration;

import static com.pnh.support.TestStaffJwt.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pnh.admin.domain.OperatorRole;
import com.pnh.shared.security.NotificationPermissions;
import com.pnh.support.IntegrationTestBase;
import com.pnh.support.TestDataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** identity staff JWTs on the console API: permission per endpoint, no CSRF, audited as preferred_username. */
class ConsoleSsoIT extends IntegrationTestBase {

    private static final String P = "notification.";

    @Test
    void noTokenNoSessionIs401() throws Exception {
        mvc.perform(get("/console/api/templates")).andExpect(status().isUnauthorized());
        mvc.perform(get("/console/api/templates").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewPermissionReadsButCannotWrite() throws Exception {
        String auditor =
                bearer("auditor", P + "dashboard.view", P + "template.view", P + "channel.view", P + "audit.view");
        mvc.perform(get("/console/api/templates").header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(status().isOk());
        mvc.perform(get("/console/api/channel-configs").header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(status().isOk());
        mvc.perform(get("/console/api/config-change-log").header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(status().isOk());
        mvc.perform(get("/console/api/alerts/rules").header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(status().isOk());
        mvc.perform(get("/console/api/notifications").header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/console/api/templates")
                        .header(HttpHeaders.AUTHORIZATION, auditor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson(TestDataFactory.unique("SSO_RO")
                                .toUpperCase(java.util.Locale.ROOT)
                                .replaceAll("[^A-Z0-9_]", "_"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void managePermissionWritesWithoutCsrfAndIsAudited() throws Exception {
        String editor = bearer("editor.sso", P + "template.view", P + "template.manage");
        String code = TestDataFactory.unique("SSO_TPL")
                .toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9_]", "_");
        mvc.perform(post("/console/api/templates")
                        .header(HttpHeaders.AUTHORIZATION, editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson(code)))
                .andExpect(status().isCreated());
        mvc.perform(get("/console/api/templates/" + code).header(HttpHeaders.AUTHORIZATION, editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created_by").value("editor.sso"));
        // channel configs need notification.channel.*
        mvc.perform(get("/console/api/channel-configs").header(HttpHeaders.AUTHORIZATION, editor))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacyOperatorRolesMapToTheIdentityPermissions() {
        org.assertj.core.api.Assertions.assertThat(OperatorRole.ADMIN.permissions())
                .containsExactlyInAnyOrderElementsOf(NotificationPermissions.ALL);
        org.assertj.core.api.Assertions.assertThat(OperatorRole.TEMPLATE_EDITOR.permissions())
                .containsExactlyInAnyOrder(P + "dashboard.view", P + "template.view", P + "template.manage");
        org.assertj.core.api.Assertions.assertThat(OperatorRole.SUPPORT_VIEWER.permissions())
                .containsExactlyInAnyOrder(
                        P + "dashboard.view", P + "message.view", P + "message.resend", P + "template.view");
    }

    private static String templateJson(String code) {
        return "{\"template_code\":\"" + code + "\",\"template_name\":\"SSO test\",\"channel_type\":\"SMS\","
                + "\"category\":\"STANDARD\",\"body_template\":\"Xin chao ${name}\"}";
    }
}
