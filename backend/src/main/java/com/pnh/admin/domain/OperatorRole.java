package com.pnh.admin.domain;

import com.pnh.shared.security.NotificationPermissions;
import java.util.List;

/**
 * Legacy console roles (local operator login). Each maps to the permissions of the matching identity role so the
 * backend checks one permission model: ADMIN = NOTIFICATION_ADMIN, TEMPLATE_EDITOR = NOTIFICATION_TEMPLATE_EDITOR,
 * SUPPORT_VIEWER = NOTIFICATION_SUPPORT.
 */
public enum OperatorRole {
    ADMIN(NotificationPermissions.ALL),
    TEMPLATE_EDITOR(List.of(
            NotificationPermissions.DASHBOARD_VIEW,
            NotificationPermissions.TEMPLATE_VIEW,
            NotificationPermissions.TEMPLATE_MANAGE)),
    SUPPORT_VIEWER(List.of(
            NotificationPermissions.DASHBOARD_VIEW,
            NotificationPermissions.MESSAGE_VIEW,
            NotificationPermissions.MESSAGE_RESEND,
            NotificationPermissions.TEMPLATE_VIEW));

    private final List<String> permissions;

    OperatorRole(List<String> permissions) {
        this.permissions = List.copyOf(permissions);
    }

    public List<String> permissions() {
        return permissions;
    }
}
