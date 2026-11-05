package com.pnh.admin.application;

import com.pnh.admin.adapter.out.OperatorRepository;
import com.pnh.admin.domain.Operator;
import com.pnh.admin.domain.OperatorRole;
import com.pnh.shared.audit.AuditContext;
import com.pnh.shared.audit.ConfigChanged;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Operator accounts (R12: no console user management in v1 — CLI only). Passwords are shown once, stored bcrypt. */
@Service
public class OperatorProvisioningService {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final OperatorRepository operators;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OperatorProvisioningService(
            OperatorRepository operators, ApplicationEventPublisher events, TransactionTemplate tx, Clock clock) {
        this.operators = operators;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    public static OperatorRole parseRole(String role) {
        try {
            return OperatorRole.valueOf(role == null ? "" : role.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw PnhException.field(
                    ErrorCode.INVALID_FIELD, "role", "role must be ADMIN, TEMPLATE_EDITOR or SUPPORT_VIEWER");
        }
    }

    /** @return the initial password (generated when {@code password} is null) */
    public String create(String actor, String username, String displayName, OperatorRole role, String password) {
        String pw = password == null || password.isBlank() ? generate() : password;
        AuditContext.runAs(
                actor,
                () -> tx.execute(s -> {
                    if (operators.existsById(username)) {
                        throw PnhException.field(
                                ErrorCode.INVALID_FIELD, "username", "Operator " + username + " already exists");
                    }
                    Operator op;
                    try {
                        op = new Operator(username, displayName, BCRYPT.encode(pw), role);
                    } catch (IllegalArgumentException e) {
                        throw PnhException.field(ErrorCode.INVALID_FIELD, "username", e.getMessage());
                    }
                    audit(actor, operators.saveAndFlush(op), "CREATE", Map.of(), Set.of("password"));
                    return null;
                }));
        return pw;
    }

    public String resetPassword(String actor, String username) {
        String pw = generate();
        AuditContext.runAs(
                actor,
                () -> tx.execute(s -> {
                    Operator op = load(username);
                    Map<String, Object> before = snapshot(op);
                    op.setPasswordHash(BCRYPT.encode(pw));
                    audit(actor, operators.saveAndFlush(op), "UPDATE", before, Set.of("password"));
                    return null;
                }));
        return pw;
    }

    public void deactivate(String actor, String username) {
        AuditContext.runAs(
                actor,
                () -> tx.execute(s -> {
                    Operator op = load(username);
                    Map<String, Object> before = snapshot(op);
                    op.deactivate();
                    audit(actor, operators.saveAndFlush(op), "DEACTIVATE", before, Set.of());
                    return null;
                }));
    }

    private Operator load(String username) {
        return operators
                .findById(username)
                .orElseThrow(() -> new PnhException(ErrorCode.NOT_FOUND, "Operator " + username + " not found"));
    }

    private void audit(String actor, Operator op, String action, Map<String, Object> before, Set<String> secrets) {
        events.publishEvent(new ConfigChanged(
                "OPERATOR",
                op.getUsername(),
                action,
                op.getVersion(),
                before,
                snapshot(op),
                Set.of(),
                secrets,
                actor,
                clock.instant()));
    }

    private static Map<String, Object> snapshot(Operator op) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("display_name", op.getDisplayName());
        m.put("role", op.getRole().name());
        m.put("status", op.getStatus().name());
        m.values().removeIf(java.util.Objects::isNull);
        return m;
    }

    private String generate() {
        byte[] b = new byte[18];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
