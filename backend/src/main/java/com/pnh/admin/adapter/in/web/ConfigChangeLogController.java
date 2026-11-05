package com.pnh.admin.adapter.in.web;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /console/api/config-change-log} — ADMIN only (T172). */
@RestController
@PreAuthorize("hasRole('ADMIN')")
class ConfigChangeLogController {

    record Entry(
            long id,
            String entityType,
            String entityId,
            String action,
            long version,
            Map<String, Object> changedFields,
            String actor,
            Instant occurredAt) {}

    record Page(List<Entry> items, long total) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    ConfigChangeLogController(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping("/console/api/config-change-log")
    Page list(
            @RequestParam(name = "entity_type", required = false) String entityType,
            @RequestParam(name = "entity_id", required = false) String entityId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (entityType != null && !entityType.isBlank()) {
            where.append(" AND entity_type = :type");
            p.addValue("type", entityType);
        }
        if (entityId != null && !entityId.isBlank()) {
            where.append(" AND entity_id = :id");
            p.addValue("id", entityId);
        }
        if (actor != null && !actor.isBlank()) {
            where.append(" AND actor = :actor");
            p.addValue("actor", actor);
        }
        if (from != null) {
            where.append(" AND occurred_at >= :from");
            p.addValue("from", Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND occurred_at < :to");
            p.addValue("to", Timestamp.from(to));
        }
        int s = Math.min(Math.max(1, size), 200);
        p.addValue("limit", s).addValue("offset", (long) Math.max(0, page) * s);
        Long total = jdbc.queryForObject("SELECT count(*) FROM pnh_config_audit_log" + where, p, Long.class);
        List<Entry> items = jdbc.query(
                "SELECT id, entity_type, entity_id, action, version, changed_fields::text AS f, actor, occurred_at "
                        + "FROM pnh_config_audit_log" + where
                        + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset",
                p,
                (rs, n) -> new Entry(
                        rs.getLong("id"),
                        rs.getString("entity_type"),
                        rs.getString("entity_id"),
                        rs.getString("action"),
                        rs.getLong("version"),
                        rs.getString("f") == null
                                ? Map.of()
                                : json.readValue(rs.getString("f"), new TypeReference<Map<String, Object>>() {}),
                        rs.getString("actor"),
                        rs.getTimestamp("occurred_at").toInstant()));
        return new Page(items, total == null ? 0 : total);
    }
}
