package com.pnh.admin.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pnh.support.ConsoleSession;
import com.pnh.support.IntegrationTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** T020 — the three filter chains. */
class SecurityChainsIT extends IntegrationTestBase {

    @Test
    void clientApiRequiresCredentials() throws Exception {
        mvc.perform(get("/api/v1/notifications/track/x"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        ClientCreds c = data.client("SEND,TRACK");
        mvc.perform(get("/api/v1/notifications/track/x")
                        .header(
                                "Authorization",
                                "Basic "
                                        + java.util.Base64.getEncoder()
                                                .encodeToString((c.clientId() + ":wrong").getBytes())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingScopeIs403ScopeDenied() throws Exception {
        ClientCreds c = data.client("TRACK");
        mvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", c.basic())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SCOPE_DENIED"));
    }

    @Test
    void inactiveClientIsRejected() throws Exception {
        ClientCreds c = data.client("SEND,TRACK", 100, true, "INACTIVE");
        mvc.perform(get("/api/v1/notifications/track/x").header("Authorization", c.basic()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CLIENT_INACTIVE"));
    }

    @Test
    void consoleRequiresSessionRoleAndCsrf() throws Exception {
        mvc.perform(get("/console/api/auth/me")).andExpect(status().isUnauthorized());

        Cookie support = login("SUPPORT_VIEWER");
        // wrong role → 403 FORBIDDEN (ADMIN-only clock override)
        var probe = mvc.perform(get("/console/api/auth/me").cookie(support)).andReturn();
        var xsrf = probe.getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(put("/console/api/admin/clock")
                        .cookie(support)
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"OFFSET\",\"offset\":\"P1D\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Cookie admin = login("ADMIN");
        // mutating call without X-XSRF-TOKEN → 403
        mvc.perform(post("/console/api/auth/logout").cookie(admin)).andExpect(status().isForbidden());
    }

    @Test
    void callbacksReachableWithoutCredentials() throws Exception {
        int status = mvc.perform(post("/api/v1/callbacks/sms/UNKNOWN_PROVIDER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isNotIn(401, 403);
    }

    private Cookie login(String role) throws Exception {
        String user = data.operator(role, "pw-123456");
        MvcResult r = mvc.perform(post("/console/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"pw-123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return ConsoleSession.sessionCookie(r);
    }
}
