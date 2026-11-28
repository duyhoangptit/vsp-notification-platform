package com.vsp.notification.dispatcher.application;

import com.vsp.notification.tracking.NotificationView;

/** Opt-out rule (FR-008): disabled + STANDARD + not critical → BLOCK; disabled + (OTP or critical) → OVERRIDE. */
public interface PreferenceRule {

    enum Decision {
        ALLOW,
        BLOCK,
        OVERRIDE
    }

    Decision evaluate(NotificationView notification);
}
