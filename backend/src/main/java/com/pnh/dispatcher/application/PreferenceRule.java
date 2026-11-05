package com.pnh.dispatcher.application;

import com.pnh.tracking.NotificationView;

/** Opt-out rule (FR-008): disabled + STANDARD + not critical → BLOCK; disabled + (OTP or critical) → OVERRIDE. */
public interface PreferenceRule {

    enum Decision {
        ALLOW,
        BLOCK,
        OVERRIDE
    }

    Decision evaluate(NotificationView notification);
}
