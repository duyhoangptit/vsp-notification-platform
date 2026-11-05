package com.pnh.coreapi.domain;

import com.pnh.shared.audit.AuditableConfigEntity;
import com.pnh.shared.domain.RecordStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Registered client system (data-model: pnh_clients). */
@Entity
@Table(name = "pnh_clients")
public class Client extends AuditableConfigEntity {

    public static final Pattern CLIENT_ID = Pattern.compile("^[a-z0-9_-]{3,50}$");

    @Id
    @Column(name = "client_id")
    private String clientId;

    @Column(name = "client_name", nullable = false)
    private String clientName;

    @Column(name = "client_secret_hash", nullable = false)
    private String clientSecretHash;

    @Column(name = "rate_limit_per_min", nullable = false)
    private int rateLimitPerMin;

    @Column(name = "scopes", nullable = false)
    private String scopes;

    @Column(name = "allow_direct_content", nullable = false)
    private boolean allowDirectContent;

    @Column(name = "kafka_signing_key_encrypted")
    private byte[] kafkaSigningKeyEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RecordStatus status;

    protected Client() {}

    public Client(
            String clientId,
            String clientName,
            String secretHash,
            int rateLimitPerMin,
            Set<ClientScope> scopes,
            boolean allowDirectContent) {
        if (clientId == null || !CLIENT_ID.matcher(clientId).matches()) {
            throw new IllegalArgumentException("client_id must match " + CLIENT_ID.pattern());
        }
        this.clientId = clientId;
        this.clientName = clientName;
        this.clientSecretHash = secretHash;
        setRateLimitPerMin(rateLimitPerMin);
        setScopes(scopes);
        this.allowDirectContent = allowDirectContent;
        this.status = RecordStatus.ACTIVE;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientName() {
        return clientName;
    }

    public String getClientSecretHash() {
        return clientSecretHash;
    }

    public int getRateLimitPerMin() {
        return rateLimitPerMin;
    }

    public boolean isAllowDirectContent() {
        return allowDirectContent;
    }

    public byte[] getKafkaSigningKeyEncrypted() {
        return kafkaSigningKeyEncrypted;
    }

    public RecordStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == RecordStatus.ACTIVE;
    }

    public Set<ClientScope> getScopes() {
        if (scopes == null || scopes.isBlank()) {
            return EnumSet.noneOf(ClientScope.class);
        }
        return Arrays.stream(scopes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(ClientScope::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ClientScope.class)));
    }

    public void setScopes(Set<ClientScope> scopes) {
        this.scopes = scopes.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    public void setRateLimitPerMin(int rateLimitPerMin) {
        if (rateLimitPerMin <= 0) {
            throw new IllegalArgumentException("rate_limit_per_min must be > 0");
        }
        this.rateLimitPerMin = rateLimitPerMin;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public void setAllowDirectContent(boolean allowDirectContent) {
        this.allowDirectContent = allowDirectContent;
    }

    public void setClientSecretHash(String clientSecretHash) {
        this.clientSecretHash = clientSecretHash;
    }

    public void setKafkaSigningKeyEncrypted(byte[] key) {
        this.kafkaSigningKeyEncrypted = key;
    }

    public void deactivate() {
        this.status = RecordStatus.INACTIVE;
    }
}
