package com.pnh.admin.domain;

import com.pnh.shared.audit.AuditableConfigEntity;
import com.pnh.shared.domain.RecordStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Console operator with exactly one role (R12). */
@Entity
@Table(name = "pnh_operators")
public class Operator extends AuditableConfigEntity {

    @Id
    private String username;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private OperatorRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RecordStatus status;

    protected Operator() {}

    public Operator(String username, String displayName, String passwordHash, OperatorRole role) {
        if (username == null || username.isBlank() || username.length() > 100) {
            throw new IllegalArgumentException("invalid username");
        }
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = java.util.Objects.requireNonNull(role, "role");
        this.status = RecordStatus.ACTIVE;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public OperatorRole getRole() {
        return role;
    }

    public RecordStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == RecordStatus.ACTIVE;
    }

    public void setPasswordHash(String hash) {
        this.passwordHash = hash;
    }

    public void setRole(OperatorRole role) {
        this.role = role;
    }

    public void deactivate() {
        this.status = RecordStatus.INACTIVE;
    }
}
