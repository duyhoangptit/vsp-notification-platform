package com.pnh.dispatcher.adapter.in.web;

import com.pnh.dispatcher.application.PreferenceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/users/{user_id}/preferences} (scope USER_DELEGATE). */
@RestController
class PreferenceController {

    record Preferences(String userId, Map<String, Boolean> channels) {}

    record UpdateRequest(@NotNull Map<String, Boolean> channels) {}

    private final PreferenceService service;

    PreferenceController(PreferenceService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/users/{userId}/preferences")
    Preferences get(@PathVariable @Size(max = 100) String userId) {
        return new Preferences(userId, service.effective(userId));
    }

    @PutMapping("/api/v1/users/{userId}/preferences")
    Preferences put(
            @PathVariable @Size(max = 100) String userId, @Valid @RequestBody UpdateRequest body, Authentication auth) {
        return new Preferences(userId, service.update(auth.getName(), userId, body.channels()));
    }
}
