package com.pnh.provider.adapter.in.web;

import com.pnh.provider.application.ChannelConfigCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /console/api/channel-configs*} — ADMIN only (T171). Secrets are write-only. */
@RestController
@RequestMapping("/console/api/channel-configs")
@PreAuthorize("hasRole('ADMIN')")
class ChannelConfigController {

    /** {@code POST}: ChannelConfigInput without {@code version} (update-only, see {@link ConfigInput}). */
    record ConfigCreateInput(
            @NotBlank String channelType,
            @NotBlank String providerKey,
            Boolean isActive,
            @NotNull Map<String, Object> settings,
            Map<String, String> secrets) {

        ChannelConfigCommandService.Input toInput() {
            return new ChannelConfigCommandService.Input(channelType, providerKey, isActive, settings, secrets, null);
        }

        @Override
        public String toString() {
            return "ConfigCreateInput[" + channelType + "/" + providerKey + "]";
        }
    }

    /** {@code PUT}: ChannelConfigInput + {@code version}. */
    record ConfigInput(
            @NotBlank String channelType,
            @NotBlank String providerKey,
            Boolean isActive,
            @NotNull Map<String, Object> settings,
            Map<String, String> secrets,
            Long version) {

        ChannelConfigCommandService.Input toInput() {
            return new ChannelConfigCommandService.Input(
                    channelType, providerKey, isActive, settings, secrets, version);
        }

        @Override
        public String toString() {
            return "ConfigInput[" + channelType + "/" + providerKey + "]";
        }
    }

    private final ChannelConfigCommandService service;

    ChannelConfigController(ChannelConfigCommandService service) {
        this.service = service;
    }

    @GetMapping
    List<ChannelConfigCommandService.ConfigView> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ChannelConfigCommandService.ConfigView create(@Valid @RequestBody ConfigCreateInput body) {
        return service.create(body.toInput());
    }

    @PutMapping("/{id}")
    ChannelConfigCommandService.ConfigView update(@PathVariable long id, @Valid @RequestBody ConfigInput body) {
        return service.update(id, body.toInput());
    }

    @PostMapping("/{id}/test")
    ChannelConfigCommandService.TestOutcome test(@PathVariable long id) {
        return service.test(id);
    }
}
