package com.pnh.tracking.adapter.in.web;

import com.pnh.tracking.NotificationView;
import com.pnh.tracking.application.TrackQueryService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/notifications/track/{client_request_id}} (scope TRACK). Never returns content/OTP. */
@RestController
class TrackController {

    record TrackResponse(
            UUID notificationId,
            String clientRequestId,
            String channelType,
            String status,
            String errorCode,
            String errorMessage,
            int retryCount,
            Instant updatedAt) {}

    private final TrackQueryService service;

    TrackController(TrackQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/notifications/track/{clientRequestId}")
    TrackResponse track(@PathVariable String clientRequestId, Authentication auth) {
        NotificationView n = service.track(auth.getName(), clientRequestId);
        return new TrackResponse(
                n.notificationId(),
                n.clientRequestId(),
                n.channelType(),
                n.status().name(),
                n.errorCode() == null ? null : n.errorCode().name(),
                n.errorMessage(),
                n.retryCount(),
                n.updatedAt());
    }
}
