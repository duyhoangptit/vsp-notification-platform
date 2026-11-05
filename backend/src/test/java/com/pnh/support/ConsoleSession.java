package com.pnh.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A signed-in operator (session cookie + XSRF token) for console API tests. The session travels as the
 * {@code PNH_SESSION} cookie, exactly like a browser (sessions are stored in PostgreSQL and shared by replicas).
 */
public record ConsoleSession(MockMvc mvc, Cookie session, Cookie xsrf, String username) {

    public static final String SESSION_COOKIE = "PNH_SESSION";

    /** The session cookie set by a successful login response. */
    public static Cookie sessionCookie(MvcResult login) {
        Cookie c = login.getResponse().getCookie(SESSION_COOKIE);
        if (c == null) {
            throw new IllegalStateException("login response has no " + SESSION_COOKIE + " cookie");
        }
        return c;
    }

    public static ConsoleSession login(MockMvc mvc, TestDataFactory data, String role) throws Exception {
        String user = data.operator(role, "pw-123456");
        MvcResult r = mvc.perform(post("/console/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"pw-123456\"}"))
                .andReturn();
        if (r.getResponse().getStatus() != 200) {
            throw new IllegalStateException("login failed: " + r.getResponse().getStatus());
        }
        Cookie s = sessionCookie(r);
        Cookie xsrf = mvc.perform(get("/console/api/auth/me").cookie(s))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        return new ConsoleSession(mvc, s, xsrf, user);
    }

    /** Adds session + CSRF to a request builder. */
    public MockHttpServletRequestBuilder with(MockHttpServletRequestBuilder b) {
        return b.cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
    }

    public MvcResult perform(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(with(b)).andReturn();
    }

    public MvcResult json(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(with(b.contentType(MediaType.APPLICATION_JSON).content(body)))
                .andReturn();
    }
}
