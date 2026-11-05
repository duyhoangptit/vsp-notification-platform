package com.pnh.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pnh.PnhApplication;
import com.pnh.admin.adapter.out.OperatorRepository;
import com.pnh.admin.domain.Operator;
import com.pnh.admin.domain.OperatorRole;
import com.pnh.contract.ContractSupport;
import com.pnh.shared.domain.NotificationIdGenerator;
import com.pnh.shared.time.ClockMode;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** T031 */
class ClockOverrideIT extends IntegrationTestBase {

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

    @AfterEach
    void resetViaApi() throws Exception {
        Session admin = login(mvc, "ADMIN");
        mvc.perform(delete("/console/api/admin/clock")
                .cookie(admin.session)
                .cookie(admin.xsrf)
                .header("X-XSRF-TOKEN", admin.xsrf.getValue()));
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
                // sessions live in PostgreSQL: the Spring Session filter must run before security, as in the server
                .addFilters(ctx.getBean("springSessionRepositoryFilter", jakarta.servlet.Filter.class))
                .apply(springSecurity())
                .build();
    }

    @Test
    void disabledOverrideIs404() throws Exception {
        MockMvc off = mvcOf(disabled);
        Session admin = login(off, "ADMIN");
        off.perform(put("/console/api/admin/clock")
                        .cookie(admin.session)
                        .cookie(admin.xsrf)
                        .header("X-XSRF-TOKEN", admin.xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"OFFSET\",\"offset\":\"P1D\"}"))
                .andExpect(status().isNotFound());
        off.perform(delete("/console/api/admin/clock")
                        .cookie(admin.session)
                        .cookie(admin.xsrf)
                        .header("X-XSRF-TOKEN", admin.xsrf.getValue()))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminOverridePropagatesAuditsAndDrivesJpaAuditing() throws Exception {
        Session admin = login(mvc, "ADMIN");
        MvcResult res = mvc.perform(put("/console/api/admin/clock")
                        .cookie(admin.session)
                        .cookie(admin.xsrf)
                        .header("X-XSRF-TOKEN", admin.xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FIXED\",\"fixed_instant\":\"2027-01-01T08:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("FIXED"))
                .andExpect(jsonPath("$.now").value("2027-01-01T08:00:00Z"))
                .andReturn();
        ContractSupport.assertConforms(ContractSupport.CONSOLE_API, res);

        PnhClock remote = replica.getBean(PnhClock.class);
        await().atMost(Duration.ofSeconds(1))
                .until(() -> remote.mode() == ClockMode.FIXED
                        && remote.instant().equals(Instant.parse("2027-01-01T08:00:00Z")));

        String username = TestDataFactory.unique("clk");
        Operator op = tx.execute(s -> operators.save(new Operator(username, "x", "h", OperatorRole.ADMIN)));
        assertThat(op.getCreatedAt()).isEqualTo(Instant.parse("2027-01-01T08:00:00Z"));
        assertThat(op.getUpdatedAt()).isEqualTo(Instant.parse("2027-01-01T08:00:00Z"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pnh_config_audit_log WHERE entity_type='CLOCK_OVERRIDE'", Integer.class))
                .isPositive();

        mvc.perform(delete("/console/api/admin/clock")
                        .cookie(admin.session)
                        .cookie(admin.xsrf)
                        .header("X-XSRF-TOKEN", admin.xsrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SYSTEM"));
        await().atMost(Duration.ofSeconds(1)).until(() -> remote.mode() == ClockMode.SYSTEM);
    }

    @Test
    void nonAdminForbiddenAndInvalidBody422() throws Exception {
        Session editor = login(mvc, "TEMPLATE_EDITOR");
        mvc.perform(put("/console/api/admin/clock")
                        .cookie(editor.session)
                        .cookie(editor.xsrf)
                        .header("X-XSRF-TOKEN", editor.xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"OFFSET\",\"offset\":\"P1D\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/console/api/admin/clock").cookie(editor.session)).andExpect(status().isOk());
        Session admin = login(mvc, "ADMIN");
        mvc.perform(put("/console/api/admin/clock")
                        .cookie(admin.session)
                        .cookie(admin.xsrf)
                        .header("X-XSRF-TOKEN", admin.xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"OFFSET\",\"offset\":\"thirty days\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/console/api/admin/clock")
                        .cookie(admin.session)
                        .cookie(admin.xsrf)
                        .header("X-XSRF-TOKEN", admin.xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FIXED\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void overrideIntoMonthWithoutPartitionCreatesThemFirst() throws Exception {
        Session admin = login(mvc, "ADMIN");
        mvc.perform(put("/console/api/admin/clock")
                        .cookie(admin.session)
                        .cookie(admin.xsrf)
                        .header("X-XSRF-TOKEN", admin.xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FIXED\",\"fixed_instant\":\"2020-01-15T00:00:00Z\"}"))
                .andExpect(status().isOk());
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
    void partitionCreationFailureRejectsOverride() throws Exception {
        // an overlapping manual partition makes creating 2019-06 impossible
        jdbc.execute("CREATE TABLE IF NOT EXISTS pnh_hist_overlap PARTITION OF pnh_notifications_history "
                + "FOR VALUES FROM ('2019-06-15') TO ('2019-07-15')");
        Session admin = login(mvc, "ADMIN");
        Instant before = clock.instant();
        mvc.perform(put("/console/api/admin/clock")
                        .cookie(admin.session)
                        .cookie(admin.xsrf)
                        .header("X-XSRF-TOKEN", admin.xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FIXED\",\"fixed_instant\":\"2019-06-20T00:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());
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
