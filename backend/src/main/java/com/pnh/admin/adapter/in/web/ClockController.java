package com.pnh.admin.adapter.in.web;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.time.ClockMode;
import com.pnh.shared.time.ClockOverrideService;
import com.pnh.shared.time.ClockState;
import com.pnh.shared.web.PnhException;
import java.security.Principal;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /console/api/admin/clock} — registered only when {@code pnh.clock.override-enabled=true} (else 404). */
@RestController
@RequestMapping("/console/api/admin/clock")
@ConditionalOnProperty(name = "pnh.clock.override-enabled", havingValue = "true")
class ClockController {

    record OverrideRequest(String mode, Instant fixedInstant, String offset) {}

    private final ClockOverrideService service;

    ClockController(ClockOverrideService service) {
        this.service = service;
    }

    @GetMapping
    ClockState get() {
        return service.state();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    ClockState override(@RequestBody OverrideRequest body, Principal principal) {
        ClockMode mode;
        try {
            mode = body.mode() == null ? null : ClockMode.valueOf(body.mode());
        } catch (IllegalArgumentException e) {
            mode = null;
        }
        if (mode == null || mode == ClockMode.SYSTEM) {
            throw PnhException.field(ErrorCode.VALIDATION_ERROR, "mode", "mode must be FIXED or OFFSET");
        }
        return service.override(mode, body.fixedInstant(), body.offset(), principal.getName());
    }

    @DeleteMapping
    @PreAuthorize("hasRole('ADMIN')")
    ClockState reset(Principal principal) {
        return service.reset(principal.getName());
    }
}
