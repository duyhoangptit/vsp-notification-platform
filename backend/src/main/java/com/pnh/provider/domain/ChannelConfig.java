package com.pnh.provider.domain;

import com.pnh.shared.audit.AuditableConfigEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Provider configuration of a channel: JSON settings + AES-GCM encrypted secrets (R13). */
@Entity
@Table(name = "pnh_channel_configs")
public class ChannelConfig extends AuditableConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_type", nullable = false)
    private String channelType;

    @Column(name = "provider_key", nullable = false)
    private String providerKey;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false, columnDefinition = "jsonb")
    private String settingsJson;

    @Column(name = "secrets_encrypted")
    private byte[] secretsEncrypted;

    @Column(name = "secrets_key_id")
    private String secretsKeyId;

    @Column(name = "last_test_at")
    private Instant lastTestAt;

    @Column(name = "last_test_result")
    private String lastTestResult;

    protected ChannelConfig() {}

    public ChannelConfig(String channelType, String providerKey) {
        this.channelType = channelType;
        this.providerKey = providerKey;
        this.settingsJson = "{}";
    }

    public Long getId() {
        return id;
    }

    public String getChannelType() {
        return channelType;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public boolean isActive() {
        return active;
    }

    public String getSettingsJson() {
        return settingsJson;
    }

    public byte[] getSecretsEncrypted() {
        return secretsEncrypted;
    }

    public String getSecretsKeyId() {
        return secretsKeyId;
    }

    public Instant getLastTestAt() {
        return lastTestAt;
    }

    public String getLastTestResult() {
        return lastTestResult;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setSettingsJson(String settingsJson) {
        this.settingsJson = settingsJson;
    }

    public void setSecrets(byte[] encrypted, String keyId) {
        this.secretsEncrypted = encrypted;
        this.secretsKeyId = keyId;
    }
}
