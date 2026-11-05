package com.pnh.tracking.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import com.pnh.shared.infra.IncompletePublicationResubmitter;
import com.pnh.support.ProviderStubs;
import com.pnh.support.SecondaryContext;
import com.pnh.support.SendSupport;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import com.pnh.tracking.application.StuckNotificationSweeper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * T178 / quickstart Q8 — an instance dies mid-load; the surviving instance resubmits incomplete work (never re-sending
 * SENDING ones) and the sweeper resolves the rest: every accepted notification ends terminal and no provider receives
 * any notification twice.
 */
class CrashRecoveryIT extends StubbedProvidersTestBase {

    @Autowired
    IncompletePublicationResubmitter resubmitter;

    @Autowired
    StuckNotificationSweeper sweeper;

    @Test
    void crashedInstanceWorkIsFinishedWithoutDuplicates() throws Exception {
        ProviderStubs.WIREMOCK.stubFor(post(urlPathEqualTo("/sms/send"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message_id\":\"c-{{randomValue length=8 type='ALPHANUMERIC'}}\"}")
                        .withTransformers("response-template")
                        .withFixedDelay(150)));
        ClientCreds c = data.client("SEND", 1_000_000, true, "ACTIVE");
        Set<UUID> accepted = ConcurrentHashMap.newKeySet();

        ConfigurableApplicationContext doomed = SecondaryContext.start();
        MockMvc instanceB = MockMvcBuilders.webAppContextSetup((WebApplicationContext) doomed)
                .apply(springSecurity())
                .build();
        for (int i = 0; i < 200; i++) {
            MvcResult r = SendSupport.send(
                    instanceB,
                    c,
                    SendSupport.json(
                            "client_request_id",
                            "crash-" + i + "-" + UUID.randomUUID(),
                            "user_id",
                            "u" + (i % 20),
                            "channel_type",
                            "SMS",
                            "recipient_target",
                            "+84901234567",
                            "content",
                            "{\"body\":\"crash test\"}"));
            accepted.add(SendSupport.notificationId(r));
        }
        doomed.close(); // the instance goes away with work in flight

        // the survivor: 3 minutes later incomplete publications are resubmitted (SENDING ones are skipped, FR-039)
        clock.setOffset(Duration.ofMinutes(3));
        resubmitter.resubmitNow();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .until(() -> nonTerminal(c.clientId()) == 0
                        || jdbc.queryForObject(
                                        "SELECT count(*) FROM pnh_notifications_history WHERE client_id = ? AND status = 'SENDING'",
                                        Integer.class,
                                        c.clientId())
                                .equals(nonTerminal(c.clientId())));
        // whatever is still SENDING has an unknown outcome → failed by the sweeper after the stuck timeout, never
        // re-sent
        clock.setOffset(Duration.ofMinutes(20));
        while (sweeper.sweepNow() > 0) {
            // drain in batches of 500
        }
        assertThat(nonTerminal(c.clientId())).isZero();

        List<LoggedRequest> calls = ProviderStubs.WIREMOCK.findAll(postRequestedFor(urlPathEqualTo("/sms/send")));
        Map<String, Long> perNotification = calls.stream()
                .map(r -> (String) JsonPath.read(r.getBodyAsString(), "$.request_id"))
                .filter(id -> accepted.contains(UUID.fromString(id)))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(perNotification.values()).allMatch(n -> n == 1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pnh_notifications_history WHERE client_id = ?",
                        Integer.class,
                        c.clientId()))
                .isEqualTo(accepted.size());
    }

    private int nonTerminal(String clientId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pnh_notifications_history WHERE client_id = ? "
                        + "AND status IN ('RECEIVED','PROCESSED','SENDING')",
                Integer.class,
                clientId);
    }
}
