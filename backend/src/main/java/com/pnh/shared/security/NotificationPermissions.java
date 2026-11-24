package com.pnh.shared.security;

import java.util.List;

/**
 * Permissions of the identity application {@code notification} (identity migration V202611041100), carried in the
 * JWT claim {@code perms} of staff tokens. Legacy console operators get the same permissions from their role (see
 * {@code OperatorRole#permissions()}), so every console endpoint checks permissions only.
 */
public final class NotificationPermissions {

    public static final String DASHBOARD_VIEW = "notification.dashboard.view";
    public static final String MESSAGE_VIEW = "notification.message.view";
    public static final String MESSAGE_RESEND = "notification.message.resend";
    public static final String TEMPLATE_VIEW = "notification.template.view";
    public static final String TEMPLATE_MANAGE = "notification.template.manage";
    public static final String CHANNEL_VIEW = "notification.channel.view";
    public static final String CHANNEL_MANAGE = "notification.channel.manage";
    public static final String CLIENT_MANAGE = "notification.client.manage";
    public static final String ALERT_MANAGE = "notification.alert.manage";
    public static final String AUDIT_VIEW = "notification.audit.view";

    public static final List<String> ALL = List.of(
            DASHBOARD_VIEW,
            MESSAGE_VIEW,
            MESSAGE_RESEND,
            TEMPLATE_VIEW,
            TEMPLATE_MANAGE,
            CHANNEL_VIEW,
            CHANNEL_MANAGE,
            CLIENT_MANAGE,
            ALERT_MANAGE,
            AUDIT_VIEW);

    private NotificationPermissions() {}
}
