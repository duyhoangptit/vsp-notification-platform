package com.pnh.dispatcher.domain;

import com.pnh.shared.audit.AuditableConfigEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A user's channel switch (absent row ⇒ enabled). UNIQUE(user_id, channel_type). */
@Entity
@Table(name = "pnh_user_preferences")
public class UserChannelPreference extends AuditableConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "channel_type", nullable = false)
    private String channelType;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    protected UserChannelPreference() {}

    public UserChannelPreference(String userId, String channelType, boolean enabled) {
        this.userId = userId;
        this.channelType = channelType;
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getChannelType() {
        return channelType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
