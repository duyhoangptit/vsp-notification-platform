package com.pnh.tracking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import com.pnh.contract.ContractSupport;
import com.pnh.shared.domain.Category;
import com.pnh.shared.domain.ContentMode;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationIdGenerator;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.shared.infra.PartitionService;
import com.pnh.support.ConsoleSession;
import com.pnh.support.IntegrationTestBase;
import com.pnh.tracking.NewNotification;
import com.pnh.tracking.StatusChange;
import com.pnh.tracking.TrackingApi;
import com.pnh.tracking.application.StatsRollupJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** T161 — rollup exactness, idempotency, HOUR/DAY volume and channel rates (SC-010). */
class DashboardStatsIT extends IntegrationTestBase {

    static final Instant DAY = Instant.parse("2036-02-10T00:00:00Z");

    @Autowired
    TrackingApi tracking;

    @Autowired
    NotificationIdGenerator ids;

    @Autowired
    PartitionService partitions;

    @Autowired
    StatsRollupJob rollup;

    private void seed(Instant at, String channel, NotificationStatus target, int n, String client) {
        clock.setFixed(at);
        for (int i = 0; i < n; i++) {
            UUID id = ids.next();
            tracking.createReceived(new NewNotification(
                    id,
                    client,
                    "ds-" + id,
                    "u",
                    "x@y.co",
                    channel,
                    Category.STANDARD,
                    ContentMode.DIRECT,
                    null,
                    null,
                    null,
                    null,
                    "b",
                    null,
                    null,
                    null));
            if (target == NotificationStatus.RECEIVED) {
                continue;
            }
            if (target == NotificationStatus.FAILED) {
                tracking.transition(StatusChange.of(id, NotificationStatus.RECEIVED, NotificationStatus.FAILED)
                        .error(ErrorCode.USER_OPTED_OUT, "x")
                        .build());
                continue;
            }
            tracking.transition(StatusChange.of(id, NotificationStatus.RECEIVED, NotificationStatus.PROCESSED)
                    .build());
            tracking.transition(StatusChange.of(id, NotificationStatus.PROCESSED, NotificationStatus.SENDING)
                    .build());
            tracking.completeSend(id, "P", "m", 0);
            if (target == NotificationStatus.DELIVERED) {
                tracking.transition(StatusChange.of(id, NotificationStatus.SENT, NotificationStatus.DELIVERED)
                        .verifiedCallback()
                        .build());
            }
        }
    }

    @Test
    void rollupAndDashboard() throws Exception {
        partitions.ensureMonths(DAY, 1);
        String client = "dash-" + UUID.randomUUID().toString().substring(0, 8);
        Instant h1 = DAY.plus(Duration.ofHours(9)).plusSeconds(60);
        Instant h2 = DAY.plus(Duration.ofHours(10)).plusSeconds(60);
        seed(h1, "EMAIL", NotificationStatus.SENT, 6, client);
        seed(h1, "EMAIL", NotificationStatus.FAILED, 2, client);
        seed(h1, "SMS", NotificationStatus.DELIVERED, 3, client);
        seed(h2, "SMS", NotificationStatus.FAILED, 1, client);
        seed(h2, "PUSH", NotificationStatus.RECEIVED, 4, client);

        rollup.recompute(DAY.plus(Duration.ofHours(9)), DAY.plus(Duration.ofHours(11)));
        rollup.recompute(DAY.plus(Duration.ofHours(9)), DAY.plus(Duration.ofHours(11))); // idempotent
        Long rows = jdbc.queryForObject(
                "SELECT sum(count) FROM pnh_notification_stats_hourly WHERE client_id = ?", Long.class, client);
        assertThat(rows).isEqualTo(16);

        ConsoleSession s = ConsoleSession.login(mvc, data, "TEMPLATE_EDITOR");
        MvcResult hour = s.perform(get("/console/api/dashboard/volume?from=2036-02-10T00:00:00Z&to=2036-02-11T00:00:00Z"
                + "&granularity=HOUR&client_id=" + client));
        assertThat(hour.getResponse().getStatus()).isEqualTo(200);
        ContractSupport.assertConforms(ContractSupport.CONSOLE_API, hour);
        String h = hour.getResponse().getContentAsString();
        assertThat((List<Integer>)
                        JsonPath.read(h, "$.buckets[?(@.bucket_start=='2036-02-10T09:00:00Z')].counts.EMAIL"))
                .containsExactly(8);
        assertThat((List<Integer>) JsonPath.read(h, "$.buckets[?(@.bucket_start=='2036-02-10T10:00:00Z')].counts.PUSH"))
                .containsExactly(4);

        MvcResult day = s.perform(get("/console/api/dashboard/volume?from=2036-02-10T00:00:00Z&to=2036-02-11T00:00:00Z"
                + "&granularity=DAY&client_id=" + client));
        String d = day.getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(d, "$.buckets[0].counts.SMS")).isEqualTo(4);

        clock.reset();
        MvcResult rates = s.perform(
                get("/console/api/dashboard/channel-rates?from=2036-02-10T00:00:00Z&to=2036-02-11T00:00:00Z"));
        ContractSupport.assertConforms(ContractSupport.CONSOLE_API, rates);
        String r = rates.getResponse().getContentAsString();
        // other tests may have used the same day; assert our channels' arithmetic from SQL truth
        Long emailOk = jdbc.queryForObject(
                "SELECT coalesce(sum(count),0) FROM pnh_notification_stats_hourly WHERE channel_type='EMAIL' "
                        + "AND status IN ('SENT','DELIVERED') AND bucket_hour >= '2036-02-10' AND bucket_hour < '2036-02-11'",
                Long.class);
        Long emailFail = jdbc.queryForObject(
                "SELECT coalesce(sum(count),0) FROM pnh_notification_stats_hourly WHERE channel_type='EMAIL' "
                        + "AND status = 'FAILED' AND bucket_hour >= '2036-02-10' AND bucket_hour < '2036-02-11'",
                Long.class);
        assertThat((List<Integer>) JsonPath.read(r, "$[?(@.channel_type=='EMAIL')].success_count"))
                .containsExactly(emailOk.intValue());
        assertThat((List<Integer>) JsonPath.read(r, "$[?(@.channel_type=='EMAIL')].failure_count"))
                .containsExactly(emailFail.intValue());
        List<Double> rate = JsonPath.read(r, "$[?(@.channel_type=='EMAIL')].success_rate");
        assertThat(rate.get(0))
                .isEqualTo((double) emailOk / (emailOk + emailFail), org.assertj.core.data.Offset.offset(0.001));
        assertThat((List<Integer>) JsonPath.read(r, "$[?(@.channel_type=='PUSH')].in_progress_count"))
                .containsExactly(4);
    }
}
