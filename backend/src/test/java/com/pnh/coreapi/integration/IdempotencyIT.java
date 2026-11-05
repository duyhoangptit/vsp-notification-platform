package com.pnh.coreapi.integration;

import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestContainers;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** T072 */
class IdempotencyIT extends StubbedProvidersTestBase {

    private final String to = com.pnh.support.MailSupport.uniqueAddress();

    private String body(String code, String requestId) {
        return json(
                "client_request_id",
                requestId,
                "user_id",
                "u1",
                "channel_type",
                "EMAIL",
                "recipient_target",
                to,
                "template_code",
                code,
                "variables",
                "{\"name\":\"An\"}");
    }

    private int keys(String clientId, String requestId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pnh_request_keys WHERE client_id=? AND client_request_id=?",
                Integer.class,
                clientId,
                requestId);
    }

    @Test
    void replayReturnsOriginalAndSendsOnce() throws Exception {
        String code = data.template("EMAIL", "STANDARD", "Hi", null, "<p>${name}</p>", "name");
        ClientCreds c = data.client("SEND");
        String req = "i-" + UUID.randomUUID();
        MvcResult first = send(mvc, c, body(code, req));
        awaitStatus(tracking, notificationId(first), NotificationStatus.SENT);
        MvcResult second = send(mvc, c, body(code, req));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(notificationId(second)).isEqualTo(notificationId(first));
        assertThat(second.getResponse().getContentAsString()).contains("\"status\":\"SENT\"");
        // a replay publishes nothing, so no asynchronous delivery can follow it
        assertThat(com.pnh.support.MailSupport.to(to)).hasSize(1);
    }

    @Test
    void concurrentIdenticalRequestsCreateOneNotification() throws Exception {
        String code = data.template("EMAIL", "STANDARD", "Hi", null, "<p>${name}</p>", "name");
        ClientCreds c = data.client("SEND", 10_000, true, "ACTIVE");
        String req = "cc-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(pool.submit((Callable<MvcResult>) () -> send(mvc, c, body(code, req))));
        }
        Set<UUID> ids = new HashSet<>();
        int accepted = 0;
        for (Future<MvcResult> f : futures) {
            MvcResult r = f.get();
            assertThat(r.getResponse().getStatus()).isIn(200, 202);
            accepted += r.getResponse().getStatus() == 202 ? 1 : 0;
            ids.add(notificationId(r));
        }
        pool.shutdown();
        assertThat(accepted).isEqualTo(1);
        assertThat(ids).hasSize(1);
        assertThat(keys(c.clientId(), req)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pnh_notifications_history WHERE client_id=? AND client_request_id=?",
                        Integer.class,
                        c.clientId(),
                        req))
                .isEqualTo(1);
    }

    @Test
    void sameRequestIdForDifferentClientsIsDistinct() throws Exception {
        String code = data.template("EMAIL", "STANDARD", "Hi", null, "<p>${name}</p>", "name");
        String req = "shared-" + UUID.randomUUID();
        MvcResult a = send(mvc, data.client("SEND"), body(code, req));
        MvcResult b = send(mvc, data.client("SEND"), body(code, req));
        assertThat(a.getResponse().getStatus()).isEqualTo(202);
        assertThat(b.getResponse().getStatus()).isEqualTo(202);
        assertThat(notificationId(a)).isNotEqualTo(notificationId(b));
    }

    @Test
    void redisDownStillExactlyOnce() throws Exception {
        String code = data.template("EMAIL", "STANDARD", "Hi", null, "<p>${name}</p>", "name");
        ClientCreds c = data.client("SEND");
        String req = "r-" + UUID.randomUUID();
        TestContainers.pauseRedis();
        try {
            MvcResult first = send(mvc, c, body(code, req));
            MvcResult second = send(mvc, c, body(code, req));
            assertThat(first.getResponse().getStatus()).isEqualTo(202);
            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            assertThat(notificationId(second)).isEqualTo(notificationId(first));
        } finally {
            TestContainers.unpauseRedis();
        }
        assertThat(keys(c.clientId(), req)).isEqualTo(1);
    }
}
