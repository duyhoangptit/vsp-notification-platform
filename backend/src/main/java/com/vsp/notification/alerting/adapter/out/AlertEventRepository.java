package com.vsp.notification.alerting.adapter.out;

import com.vsp.notification.alerting.domain.AlertEvent;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JDBC access to the append-only alert history. */
@Repository
public class AlertEventRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public AlertEventRepository(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(AlertEvent e) {
        jdbc.update(
                """
                INSERT INTO pnh_alert_events (rule_code, channel_type, kind, measured_value, threshold, window_minutes, occurred_at,
                  recipients, send_status, send_error, details)
                VALUES (:rule, :channel, :kind, :value, :threshold, :window, :at, :recipients, :status, :error, CAST(:details AS jsonb))""",
                new MapSqlParameterSource()
                        .addValue("rule", e.ruleCode())
                        .addValue("channel", e.channelType())
                        .addValue("kind", e.kind().name())
                        .addValue("value", e.measuredValue())
                        .addValue("threshold", e.threshold())
                        .addValue("window", e.windowMinutes())
                        .addValue("at", Timestamp.from(e.occurredAt()))
                        .addValue("recipients", e.recipients().toArray(String[]::new))
                        .addValue("status", e.sendStatus().name())
                        .addValue("error", e.sendError())
                        .addValue("details", e.details() == null ? null : json.writeValueAsString(e.details())));
    }

    /** Latest event per (rule, channel) — the durable alert state. */
    public List<AlertEvent> latestPerRuleAndChannel() {
        return jdbc.query("""
                SELECT DISTINCT ON (rule_code, coalesce(channel_type, '')) * FROM pnh_alert_events
                ORDER BY rule_code, coalesce(channel_type, ''), occurred_at DESC, id DESC""", Map.of(), this::map);
    }

    public Optional<AlertEvent> latest(String ruleCode, String channel) {
        return jdbc
                .query("""
                SELECT * FROM pnh_alert_events WHERE rule_code = :rule AND coalesce(channel_type, '') = coalesce(:channel, '')
                ORDER BY occurred_at DESC, id DESC LIMIT 1""", new MapSqlParameterSource("rule", ruleCode).addValue("channel", channel), this::map)
                .stream()
                .findFirst();
    }

    public record Page(List<AlertEvent> items, long total) {}

    public Page search(String rule, String channel, Instant from, Instant to, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (rule != null && !rule.isBlank()) {
            where.append(" AND rule_code = :rule");
            p.addValue("rule", rule);
        }
        if (channel != null && !channel.isBlank()) {
            where.append(" AND channel_type = :channel");
            p.addValue("channel", channel);
        }
        if (from != null) {
            where.append(" AND occurred_at >= :from");
            p.addValue("from", Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND occurred_at < :to");
            p.addValue("to", Timestamp.from(to));
        }
        p.addValue("limit", size).addValue("offset", (long) page * size);
        Long total = jdbc.queryForObject("SELECT count(*) FROM pnh_alert_events" + where, p, Long.class);
        List<AlertEvent> items = jdbc.query(
                "SELECT * FROM pnh_alert_events" + where
                        + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset",
                p,
                this::map);
        return new Page(items, total == null ? 0 : total);
    }

    private AlertEvent map(ResultSet rs, int row) throws SQLException {
        Array arr = rs.getArray("recipients");
        List<String> recipients = arr == null ? List.of() : new ArrayList<>(Arrays.asList((String[]) arr.getArray()));
        String details = rs.getString("details");
        Object window = rs.getObject("window_minutes");
        return new AlertEvent(
                rs.getLong("id"),
                rs.getString("rule_code"),
                rs.getString("channel_type"),
                AlertEvent.Kind.valueOf(rs.getString("kind")),
                rs.getBigDecimal("measured_value"),
                rs.getBigDecimal("threshold"),
                window == null ? null : ((Number) window).intValue(),
                rs.getTimestamp("occurred_at").toInstant(),
                recipients,
                AlertEvent.SendStatus.valueOf(rs.getString("send_status")),
                rs.getString("send_error"),
                details == null ? null : json.readValue(details, new TypeReference<Map<String, Object>>() {}));
    }
}
