package com.pnh.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pnh.support.ConsoleSession;
import com.pnh.support.IntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** T019 — console auth endpoints conform to console-api.openapi.yaml. Runs with the IT suite (failsafe). */
class ConsoleAuthContractIT extends IntegrationTestBase {

    @Test
    void loginMeLogoutConformToContract() throws Exception {
        String user = data.operator("ADMIN", "pw-123456");
        MvcResult login = mvc.perform(post("/console/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"pw-123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(user))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();
        ContractSupport.assertConforms(ContractSupport.CONSOLE_API, login);
        Cookie session = ConsoleSession.sessionCookie(login);

        MvcResult me = mvc.perform(get("/console/api/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").exists())
                .andReturn();
        ContractSupport.assertConforms(ContractSupport.CONSOLE_API, me);

        // CSRF token cookie is issued for the SPA; logout is a mutating call that needs it
        MvcResult csrfProbe =
                mvc.perform(get("/console/api/auth/me").cookie(session)).andReturn();
        Cookie xsrf = csrfProbe.getResponse().getCookie("XSRF-TOKEN");
        MvcResult logout = mvc.perform(post("/console/api/auth/logout")
                        .cookie(session)
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent())
                .andReturn();
        ContractSupport.assertConforms(ContractSupport.CONSOLE_API, logout);
    }

    @Test
    void badCredentialsGive401Problem() throws Exception {
        MvcResult res = mvc.perform(post("/console/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andReturn();
        ContractSupport.assertConforms(ContractSupport.CONSOLE_API, res);
    }

    @Test
    void meWithoutSessionIs401() throws Exception {
        mvc.perform(get("/console/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
