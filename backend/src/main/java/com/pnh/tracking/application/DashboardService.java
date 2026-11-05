package com.pnh.tracking.application;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dashboard reads from the hourly rollups (US9, SC-010): success = SENT+DELIVERED, failure = FAILED, rest in progress. */
@Service
public class DashboardService {

    static final Duration MAX_RANGE = Duration.ofDays(400);

    public record Bucket(Instant bucketStart, Map<String, Long> counts) {}

    public record Volume(List<Bucket> buckets) {}

    public record ChannelRate(
            String channelType,
            long successCount,
            long failureCount,
            long inProgressCount,
            double successRate,
            double failureRate) {}

    private final JdbcClient jdbc;

    public DashboardService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Volume volume(Instant from, Instant to, String granularity, String clientId) {
        check(from, to);
        boolean day = "DAY".equals(granularity);
        Map<Instant, Map<String, Long>> buckets = new TreeMap<>();
        String sql =
                "SELECT bucket_hour, channel_type, sum(count) FROM pnh_notification_stats_hourly WHERE bucket_hour >= :from "
                        + "AND bucket_hour < :to"
                        + (clientId == null || clientId.isBlank() ? "" : " AND client_id = :client")
                        + " GROUP BY 1, 2 ORDER BY 1";
        JdbcClient.StatementSpec spec =
                jdbc.sql(sql).param("from", Timestamp.from(from)).param("to", Timestamp.from(to));
        if (clientId != null && !clientId.isBlank()) {
            spec = spec.param("client", clientId);
        }
        spec.query((rs, n) -> {
                    Instant hour = rs.getTimestamp(1).toInstant();
                    Instant key = day ? hour.truncatedTo(ChronoUnit.DAYS) : hour;
                    buckets.computeIfAbsent(key, k -> new TreeMap<>()).merge(rs.getString(2), rs.getLong(3), Long::sum);
                    return null;
                })
                .list();
        List<Bucket> out = new ArrayList<>();
        buckets.forEach((k, v) -> out.add(new Bucket(k, v)));
        return new Volume(out);
    }

    @Transactional(readOnly = true)
    public List<ChannelRate> channelRates(Instant from, Instant to) {
        check(from, to);
        return jdbc.sql("""
                SELECT channel_type,
                  coalesce(sum(count) FILTER (WHERE status IN ('SENT','DELIVERED')), 0),
                  coalesce(sum(count) FILTER (WHERE status = 'FAILED'), 0),
                  coalesce(sum(count) FILTER (WHERE status IN ('RECEIVED','PROCESSED','SENDING')), 0)
                FROM pnh_notification_stats_hourly WHERE bucket_hour >= :from AND bucket_hour < :to
                GROUP BY channel_type HAVING sum(count) > 0 ORDER BY channel_type""")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> {
                    long ok = rs.getLong(2);
                    long fail = rs.getLong(3);
                    long finished = ok + fail;
                    return new ChannelRate(
                            rs.getString(1),
                            ok,
                            fail,
                            rs.getLong(4),
                            finished == 0 ? 0 : (double) ok / finished,
                            finished == 0 ? 0 : (double) fail / finished);
                })
                .list();
    }

    private static void check(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw PnhException.field(ErrorCode.VALIDATION_ERROR, "from", "from must be before to");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw PnhException.field(ErrorCode.VALIDATION_ERROR, "to", "Range must not exceed 400 days");
        }
    }
}
