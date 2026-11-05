package com.pnh.alerting.adapter.in.web;

import com.pnh.alerting.adapter.out.AlertEventRepository;
import com.pnh.alerting.application.AlertMailer;
import com.pnh.alerting.application.AlertSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /console/api/alerts/*} — ADMIN only (T154). */
@RestController
@RequestMapping("/console/api/alerts")
@PreAuthorize("hasRole('ADMIN')")
class AlertConsoleController {

    record NewRecipient(
            @NotBlank @Size(max = 254) String email,
            @Size(max = 150) String displayName) {}

    record RecipientUpdate(
            String displayName, Boolean isActive, @NotNull Long version) {}

    record RuleUpdate(
            @NotNull Boolean enabled,
            @NotNull @Min(0) BigDecimal threshold,
            @NotNull @Min(1) @Max(1440) Integer windowMinutes,
            @Min(1) Integer minSample,
            @NotNull @Min(5) @Max(1440) Integer cooldownMinutes,
            @NotNull Long version) {}

    record TestResult(String sendStatus, String error) {}

    private final AlertSettingsService settings;
    private final AlertEventRepository events;
    private final AlertMailer mailer;

    AlertConsoleController(AlertSettingsService settings, AlertEventRepository events, AlertMailer mailer) {
        this.settings = settings;
        this.events = events;
        this.mailer = mailer;
    }

    @GetMapping("/recipients")
    List<AlertSettingsService.RecipientView> recipients() {
        return settings.recipients();
    }

    @PostMapping("/recipients")
    @ResponseStatus(HttpStatus.CREATED)
    AlertSettingsService.RecipientView add(@Valid @RequestBody NewRecipient body) {
        return settings.addRecipient(body.email(), body.displayName());
    }

    @PutMapping("/recipients/{id}")
    AlertSettingsService.RecipientView update(@PathVariable long id, @Valid @RequestBody RecipientUpdate body) {
        return settings.updateRecipient(id, body.displayName(), body.isActive(), body.version());
    }

    @DeleteMapping("/recipients/{id}")
    ResponseEntity<Void> remove(@PathVariable long id, @RequestParam long version) {
        settings.removeRecipient(id, version);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/recipients/test")
    TestResult test() {
        AlertMailer.Result r = mailer.send(
                "[PNH][TEST] Test alert",
                "This is a test alert from the Project Notification Hub console. No action is required.");
        return new TestResult(r.status().name(), r.error());
    }

    @GetMapping("/rules")
    List<AlertSettingsService.RuleView> rules() {
        return settings.rules();
    }

    @PutMapping("/rules/{ruleCode}")
    AlertSettingsService.RuleView updateRule(@PathVariable String ruleCode, @Valid @RequestBody RuleUpdate body) {
        return settings.updateRule(
                ruleCode,
                body.enabled(),
                body.threshold(),
                body.windowMinutes(),
                body.minSample(),
                body.cooldownMinutes(),
                body.version());
    }

    @GetMapping("/events")
    AlertEventRepository.Page events(
            @RequestParam(name = "rule_code", required = false) String ruleCode,
            @RequestParam(name = "channel_type", required = false) String channelType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return events.search(ruleCode, channelType, from, to, Math.max(0, page), Math.min(Math.max(1, size), 200));
    }
}
