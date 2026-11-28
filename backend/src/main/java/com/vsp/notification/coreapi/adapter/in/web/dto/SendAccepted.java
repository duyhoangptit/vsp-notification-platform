package com.vsp.notification.coreapi.adapter.in.web.dto;

import java.time.Instant;
import java.util.UUID;

public record SendAccepted(UUID notificationId, String clientRequestId, String status, Instant createdAt) {}
