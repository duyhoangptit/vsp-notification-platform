package com.pnh.coreapi.application;

import com.pnh.tracking.NotificationView;

/** {@code replay=true}: the client request id was already accepted; nothing new was created. */
public record SubmitResult(NotificationView notification, boolean replay) {}
