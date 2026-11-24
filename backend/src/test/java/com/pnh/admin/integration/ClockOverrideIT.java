package com.pnh.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pnh.PnhApplication;
import com.pnh.admin.adapter.out.OperatorRepository;
import com.pnh.admin.domain.Operator;
import com.pnh.admin.domain.OperatorRole;
import com.pnh.shared.domain.NotificationIdGenerator;
import com.pnh.shared.time.ClockMode;
import com.pnh.shared.time.ClockOverrideService;
import com.pnh.shared.time.PnhClock;
import com.pnh.support.IntegrationTestBase;
import com.pnh.support.NotificationFixtures;
import com.pnh.support.SecondaryContext;
import com.pnh.support.TestDataFactory;
import com.pnh.tracking.TrackingApi;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * T031 — the system clock is owned by vsp-catalog-service, which publishes it on Redis key {@code vsp:clock:override}
 * and topic {@code vsp:clock}. These tests play the catalog's role by publishing the contract JSON directly.
 */
class ClockOverrideIT extends IntegrationTestBase {

    static final String KEY = "vsp:clock:override";
    static final String TOPIC = "vsp:clock";

    static ConfigurableApplicationContext replica;
    static ConfigurableApplicationContext disabled;

    @Autowired
    OperatorRepository operators;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TrackingApi tracking;

    @Autowired
    NotificationIdGenerator ids;

    @Autowired
    RedissonClient redisson;

    @Autowired
    ClockOverrideService clockService;

    @BeforeAll
    static void startReplicas() {
        replica = SecondaryContext.start();
        disabled = SecondaryContext.start("pnh.clock.override-enabled=false");
    }

    @AfterAll
    static void stop() {
        replica.close();
        disabled.close();
    }

    /** What vsp-catalog-service does on PUT /api/v1/admin/system-clock. */
    private void catalogPublishes(String mode, String fixedInstant, String offset) {
        String payload = "{\"mode\":\"" + mode + "\""
                + (fixedInstant == null ? "" : ",\"fixed_instant\":\"" + fixedInstant + "\"")
                + (offset == null ? "" : ",\"offset\":\"" + offset + "\"")
                + ",\"changed_by\":\"catalog-admin\",\"changed_at\":\"" + clock.realInstant() + "\"}";
        if ("SYSTEM".equals(mode)) {
            redisson.getBucket(KEY, StringCodec.INSTANCE).delete();
        } else {
            redisson.getBucket(KEY, StringCodec.INSTANCE).set(payload);
        }
        redisson.getTopic(TOPIC, StringCodec.INSTANCE).publish(payload);
    }

    @AfterEach
    void resetViaCatalog() {
        catalogPublishes("SYSTEM", null, null);
        await().atMost(Duration.ofSeconds(2)).until(() -> clock.mode() == ClockMode.SYSTEM);
    }

    record Session(Cookie session, Cookie xsrf) {}

    private Session login(MockMvc target, String role) throws Exception {
        String user = data.operator(role, "pw-123456");
        MvcResult r = target.perform(post("/console/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"pw-123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie s = com.pnh.support.ConsoleSession.sessionCookie(r);
        Cookie xsrf = target.perform(get("/console/api/auth/me").cookie(s))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        return new Session(s, xsrf);
    }

    private static MockMvc mvcOf(ConfigurableApplicationContext ctx) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) ctx)
                .addFilters(ctx.getBean("springSessionRepositoryFilter", jakarta.servlet.Filter.class))
                .apply(springSecurity())
                .build();
    }

    @Test
    void publishedOverrideReachesEveryInstanceAndDrivesJpaAuditing() throws Exception {
        catalogPublishes("FIXED", "2027-01-01T08:00:00Z", null);

        Instant fixed = Instant.parse("2027-01-01T08:00:00Z");
        await().atMost(Duration.ofSeconds(2)).until(() -> clock.instant().equals(fixed));
        PnhClock remote = replica.getBean(PnhClock.class);
        await().atMost(Duration.ofSeconds(2))
                .until(() ->
                        remote.mode() == ClockMode.FIXED && remote.instant().equals(fixed));
        // an instance with override disabled keeps real time
        assertThat(disabled.getBean(PnhClock.class).mode()).isEqualTo(ClockMode.SYSTEM);

        String username = TestDataFactory.unique("clk");
        Operator op = tx.execute(s -> operators.save(new Operator(username, "x", "h", OperatorRole.ADMIN)));
        assertThat(op.getCreatedAt()).isEqualTo(fixed);

        Session editor = login(mvc, "TEMPLATE_EDITOR");
        mvc.perform(get("/console/api/admin/clock").cookie(editor.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("FIXED"))
                .andExpect(jsonPath("$.now").value("2027-01-01T08:00:00Z"))
                .andExpect(jsonPath("$.changed_by").value("catalog-admin"));

        catalogPublishes("SYSTEM", null, null);
        await().atMost(Duration.ofSeconds(2)).until(() -> remote.mode() == ClockMode.SYSTEM);
    }

    @Test
    void notificationNoLongerAcceptsWrites() throws Exception {
        Session admin = login(mvc, "ADMIN");
        mvc.perform(put("/console/api/admin/clock")
                        .cookie(admin.session())
                        .cookie(admin.xsrf())
                        .header("X-XSRF-TOKEN", admin.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"OFFSET\",\"offset\":\"P1D\"}"))
                .andExpect(status().isMethodNotAllowed());
        MockMvc off = mvcOf(disabled);
        Session offAdmin = login(off, "ADMIN");
        off.perform(get("/console/api/admin/clock").cookie(offAdmin.session())).andExpect(status().isNotFound());
    }

    @Test
    void offsetOverride() {
        catalogPublishes("OFFSET", null, "P30D");
        await().atMost(Duration.ofSeconds(2)).until(() -> clock.mode() == ClockMode.OFFSET);
        assertThat(Duration.between(clock.realInstant(), clock.instant()).toDays())
                .isEqualTo(30);
    }

    @Test
    void resyncCatchesUpWithoutBroadcast() {
        redisson.getBucket(KEY, StringCodec.INSTANCE)
                .set("{\"mode\":\"FIXED\",\"fixed_instant\":\"2028-02-29T00:00:00Z\",\"changed_by\":\"x\","
                        + "\"changed_at\":\"2026-11-23T00:00:00Z\"}");
        clockService.scheduledResync();
        assertThat(clock.instant()).isEqualTo(Instant.parse("2028-02-29T00:00:00Z"));
    }

    @Test
    void overrideIntoMonthWithoutPartitionCreatesThemFirst() {
        catalogPublishes("FIXED", "2020-01-15T00:00:00Z", null);
        await().atMost(Duration.ofSeconds(2)).until(() -> clock.mode() == ClockMode.FIXED);
        for (String m : new String[] {"2020m01", "2020m02", "2020m03", "2020m04"}) {
            assertThat(jdbc.queryForObject(
                            "SELECT to_regclass('pnh_notifications_history_y" + m + "') IS NOT NULL", Boolean.class))
                    .isTrue();
        }
        var n = tracking.createReceived(NotificationFixtures.email(ids));
        String partition = jdbc.queryForObject(
                "SELECT tableoid::regclass::text FROM pnh_notifications_history WHERE notification_id = ?",
                String.class,
                n.notificationId());
        assertThat(partition).isEqualTo("pnh_notifications_history_y2020m01");
    }

    @Test
    void partitionCreationFailureIgnoresOverride() throws Exception {
        // an overlapping manual partition makes creating 2019-06 impossible
        jdbc.execute("CREATE TABLE IF NOT EXISTS pnh_hist_overlap PARTITION OF pnh_notifications_history "
                + "FOR VALUES FROM ('2019-06-15') TO ('2019-07-15')");
        Instant before = clock.instant();
        catalogPublishes("FIXED", "2019-06-20T00:00:00Z", null);
        clockService.scheduledResync(); // synchronous path: must not switch either
        Thread.sleep(300);
        assertThat(clock.mode()).isEqualTo(ClockMode.SYSTEM);
        assertThat(clock.instant()).isAfterOrEqualTo(before);
    }

    @Test
    void prodProfileWithOverrideFailsToStart() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(PnhApplication.class)
                        .profiles("test", "prod")
                        .run(
                                "--pnh.clock.override-enabled=true",
                                "--spring.main.web-application-type=none",
                                "--spring.datasource.url=" + com.pnh.support.TestContainers.POSTGRES.getJdbcUrl(),
                                "--spring.datasource.username=pnh",
                                "--spring.datasource.password=pnh",
                                "--spring.flyway.enabled=false",
                                "--pnh.redis.address=" + com.pnh.support.TestContainers.redisAddress(),
                                "--spring.kafka.bootstrap-servers="
                                        + com.pnh.support.TestContainers.KAFKA.getBootstrapServers()))
                .hasStackTraceContaining("override-enabled must not be true with profile 'prod'");
    }
}
