package com.vsp.notification.coreapi.application;

import com.vsp.notification.tracking.NotificationView;

/** {@code replay=true}: the client request id was already accepted; nothing new was created. */
public record SubmitResult(NotificationView notification, boolean replay) {}
