package com.pnh.tracking.adapter.in.web;

import com.pnh.tracking.application.NotificationSearchService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Delivery Audit API (US8) — ADMIN and SUPPORT_VIEWER. */
@RestController
@RequestMapping("/console/api/notifications")
@PreAuthorize("hasAnyRole('ADMIN','SUPPORT_VIEWER')")
class NotificationConsoleController {

    private final NotificationSearchService service;

    NotificationConsoleController(NotificationSearchService service) {
        this.service = service;
    }

    @GetMapping
    NotificationSearchService.Page search(
            @RequestParam(name = "client_request_id", required = false) String clientRequestId,
            @RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(name = "recipient_target", required = false) String recipientTarget,
            @RequestParam(name = "channel_type", required = false) String channelType,
            @RequestParam(required = false) String status,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(new NotificationSearchService.Criteria(
                clientRequestId, userId, recipientTarget, channelType, status, clientId, from, to, page, size));
    }

    @GetMapping("/{id}")
    NotificationSearchService.Detail detail(@PathVariable UUID id) {
        return service.detail(id);
    }
}
