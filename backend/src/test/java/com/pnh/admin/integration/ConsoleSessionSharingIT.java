package com.pnh.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.support.ConsoleSession;
import com.pnh.support.IntegrationTestBase;
import com.pnh.support.SecondaryContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Console sessions are shared by all replicas (2–4 behind a plain load balancer, plan.md): an operator signed in on
 * one instance stays signed in when the next request lands on another, and signing out ends it everywhere.
 */
class ConsoleSessionSharingIT extends IntegrationTestBase {

    static ConfigurableApplicationContext replica;

    @BeforeAll
    static void start() {
        replica = SecondaryContext.start();
    }

    @AfterAll
    static void stop() {
        replica.close();
    }

    private static HttpResponse<String> me(String sessionCookie) throws Exception {
        String port = replica.getEnvironment().getProperty("local.server.port");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/console/api/auth/me"))
                .header("Cookie", sessionCookie)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void sessionFromOneReplicaIsValidOnAnother() throws Exception {
        ConsoleSession admin = ConsoleSession.login(mvc, data, "ADMIN");
        String cookie = admin.session().getName() + "=" + admin.session().getValue();

        HttpResponse<String> onReplica = me(cookie);
        assertThat(onReplica.statusCode()).isEqualTo(200);
        assertThat(onReplica.body()).contains(admin.username());

        admin.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/console/api/auth/logout"));
        assertThat(me(cookie).statusCode()).isEqualTo(401);
    }
}
