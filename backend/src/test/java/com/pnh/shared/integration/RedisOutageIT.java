package com.pnh.shared.integration;

import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.shared.infra.DistributedLockService;
import com.pnh.shared.infra.RedisAvailability;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestContainers;
import com.pnh.support.TestDataFactory.ClientCreds;
import com.pnh.tracking.NotificationView;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** T179 / quickstart Q14 — Redis outage degrades but never loses or duplicates notifications. */
class RedisOutageIT extends StubbedProvidersTestBase {

    @Autowired
    RedisAvailability availability;

    @Autowired
    DistributedLockService locks;

    @Test
    void intakeAndJobsSurviveRedisOutage() throws Exception {
        ClientCreds c = data.client("SEND", 100_000, true, "ACTIVE");
        Set<UUID> accepted = new HashSet<>();
        TestContainers.pauseRedis();
        try {
            for (int i = 0; i < 50; i++) {
                MvcResult r = send(
                        mvc,
                        c,
                        json(
                                "client_request_id",
                                "ro-" + i + "-" + UUID.randomUUID(),
                                "user_id",
                                "u",
                                "channel_type",
                                "SMS",
                                "recipient_target",
                                "+84901234567",
                                "content",
                                "{\"body\":\"outage\"}"));
                assertThat(r.getResponse().getStatus()).isEqualTo(202);
                accepted.add(notificationId(r));
            }
            // duplicates are still rejected (Postgres is authoritative)
            String dup = "ro-dup-" + UUID.randomUUID();
            String body = json(
                    "client_request_id",
                    dup,
                    "user_id",
                    "u",
                    "channel_type",
                    "SMS",
                    "recipient_target",
                    "+84901234567",
                    "content",
                    "{\"body\":\"dup\"}");
            UUID first = notificationId(send(mvc, c, body));
            MvcResult again = send(mvc, c, body);
            assertThat(again.getResponse().getStatus()).isEqualTo(200);
            assertThat(notificationId(again)).isEqualTo(first);

            // single-runner jobs fall back to the Postgres advisory lock after 3 failed Redis attempts
            AtomicBoolean ran = new AtomicBoolean();
            for (int i = 0; i < 3 && !ran.get(); i++) {
                locks.tryRunExclusive("outage-probe", () -> ran.set(true));
            }
            assertThat(ran).isTrue();

            assertThat(availability.isDegraded()).isTrue();
            String health = mvc.perform(get("/actuator/health"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat((String) JsonPath.read(health, "$.components.redisAvailability.status"))
                    .isEqualTo("DEGRADED");
            assertThat(mvc.perform(get("/actuator/prometheus"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString())
                    .containsPattern("pnh_redis_fallback_active\\{[^}]*\\} 1\\.0");
        } finally {
            TestContainers.unpauseRedis();
        }
        for (UUID id : accepted) {
            await().atMost(Duration.ofSeconds(30))
                    .until(() ->
                            tracking.findById(id).map(NotificationView::status).orElse(null)
                                    == NotificationStatus.SENT);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pnh_notifications_history WHERE client_id = ?",
                        Integer.class,
                        c.clientId()))
                .isEqualTo(51);
        // a successful Redis operation clears the fallback flag
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            locks.tryRunExclusive("outage-recovery-probe", () -> {});
            return !availability.isDegraded();
        });
    }
}
