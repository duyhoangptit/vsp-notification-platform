package com.pnh.admin.application;

import com.pnh.shared.audit.ChangeDiff;
import com.pnh.shared.audit.ConfigChanged;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends every {@link ConfigChanged} to pnh_config_audit_log (T048, constitution V). Runs synchronously in the
 * publishing transaction so a config change and its audit entry commit (or roll back) together.
 */
@Component
public class ConfigAuditRecorder {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ConfigAuditRecorder(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void on(ConfigChanged event) {
        jdbc.sql("""
                INSERT INTO pnh_config_audit_log (entity_type, entity_id, action, version, changed_fields, actor, occurred_at)
                VALUES (:type, :id, :action, :version, CAST(:fields AS jsonb), :actor, :at)""")
                .param("type", event.entityType())
                .param("id", event.entityId())
                .param("action", event.action())
                .param("version", event.version())
                .param("fields", json.writeValueAsString(ChangeDiff.diff(event)))
                .param("actor", event.actor())
                .param("at", Timestamp.from(event.occurredAt()))
                .update();
    }
}
