package com.pnh.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pnh.admin.adapter.out.OperatorRepository;
import com.pnh.admin.domain.Operator;
import com.pnh.admin.domain.OperatorRole;
import com.pnh.shared.audit.AuditContext;
import com.pnh.shared.audit.ConfigChanged;
import com.pnh.support.IntegrationTestBase;
import com.pnh.support.TestDataFactory;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/** T018 */
class ConfigAuditTrailIT extends IntegrationTestBase {

    @Autowired
    OperatorRepository operators;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ApplicationEventPublisher events;

    @Test
    void auditColumnsVersionAndAppendOnlyLog() {
        String username = TestDataFactory.unique("audit");
        clock.setFixed(Instant.parse("2026-11-03T09:00:00Z"));
        Operator created = AuditContext.runAs(
                "alice",
                () -> tx.execute(
                        s -> operators.save(new Operator(username, "A", "hash", OperatorRole.SUPPORT_VIEWER))));
        assertThat(created.getCreatedBy()).isEqualTo("alice");
        assertThat(created.getCreatedAt()).isEqualTo(Instant.parse("2026-11-03T09:00:00Z"));
        assertThat(created.getVersion()).isZero();

        clock.setFixed(Instant.parse("2026-11-03T09:05:00Z"));
        Operator updated = AuditContext.runAs(
                "bob",
                () -> tx.execute(s -> {
                    Operator op = operators.findById(username).orElseThrow();
                    op.setRole(OperatorRole.TEMPLATE_EDITOR);
                    events.publishEvent(new ConfigChanged(
                            "OPERATOR",
                            username,
                            "UPDATE",
                            op.getVersion() + 1,
                            Map.of("role", "SUPPORT_VIEWER", "password_hash", "x"),
                            Map.of("role", "TEMPLATE_EDITOR", "password_hash", "y"),
                            Set.of("password_hash"),
                            Set.of("password_hash"),
                            "bob",
                            clock.instant()));
                    return operators.saveAndFlush(op);
                }));
        assertThat(updated.getUpdatedBy()).isEqualTo("bob");
        assertThat(updated.getCreatedBy()).isEqualTo("alice");
        assertThat(updated.getUpdatedAt()).isEqualTo(Instant.parse("2026-11-03T09:05:00Z"));
        assertThat(updated.getVersion()).isEqualTo(1);

        // stale version → optimistic lock failure (mapped to 409 VERSION_CONFLICT by GlobalProblemHandler)
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    Operator stale = operators.findById(username).orElseThrow();
                    jdbc.update("UPDATE pnh_operators SET version = version + 1 WHERE username = ?", username);
                    stale.setRole(OperatorRole.ADMIN);
                    operators.saveAndFlush(stale);
                }))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT action, version, actor, changed_fields::text AS f FROM pnh_config_audit_log WHERE entity_type='OPERATOR' AND entity_id=?",
                username);
        assertThat(row.get("action")).isEqualTo("UPDATE");
        assertThat(row.get("actor")).isEqualTo("bob");
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat((String) row.get("f"))
                .contains("\"role\"")
                .contains("TEMPLATE_EDITOR")
                .contains("***changed***")
                .doesNotContain("\"y\"");

        assertThatThrownBy(() ->
                        jdbc.update("UPDATE pnh_config_audit_log SET actor='mallory' WHERE entity_id=?", username))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM pnh_config_audit_log WHERE entity_id=?", username))
                .hasMessageContaining("append-only");
    }
}
