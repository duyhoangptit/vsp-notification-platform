package com.vsp.notification.tracking.adapter.in.web;

import com.vsp.notification.tracking.application.DashboardService;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operations dashboard (US9) — every role. */
@RestController
@RequestMapping("/console/api/dashboard")
class DashboardController {

    private final DashboardService service;

    DashboardController(DashboardService service) {
        this.service = service;
    }

    @PreAuthorize("hasAnyAuthority('notification.dashboard.view')")
    @GetMapping("/volume")
    DashboardService.Volume volume(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam String granularity,
            @RequestParam(name = "client_id", required = false) String clientId) {
        return service.volume(from, to, granularity, clientId);
    }

    @PreAuthorize("hasAnyAuthority('notification.dashboard.view')")
    @GetMapping("/channel-rates")
    List<DashboardService.ChannelRate> rates(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.channelRates(from, to);
    }
}
