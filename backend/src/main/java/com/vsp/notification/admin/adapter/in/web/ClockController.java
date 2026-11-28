package com.vsp.notification.admin.adapter.in.web;

import com.vsp.notification.shared.time.ClockOverrideService;
import com.vsp.notification.shared.time.ClockState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /console/api/admin/clock} — read-only view of the system time (override banner). The override itself is
 * managed centrally by vsp-catalog-service ({@code PUT/DELETE /api/v1/admin/system-clock}). Registered only when
 * {@code pnh.clock.override-enabled=true} (else 404).
 */
@RestController
@RequestMapping("/console/api/admin/clock")
@ConditionalOnProperty(name = "pnh.clock.override-enabled", havingValue = "true")
class ClockController {

    private final ClockOverrideService service;

    ClockController(ClockOverrideService service) {
        this.service = service;
    }

    @GetMapping
    ClockState get() {
        return service.state();
    }
}
