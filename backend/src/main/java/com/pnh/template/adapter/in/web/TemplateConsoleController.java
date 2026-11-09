package com.pnh.template.adapter.in.web;

import com.pnh.template.adapter.in.web.dto.TemplateCreateInput;
import com.pnh.template.adapter.in.web.dto.TemplateInput;
import com.pnh.template.adapter.in.web.dto.TemplateView;
import com.pnh.template.application.TemplateCommandService;
import com.pnh.template.application.TemplatePreviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
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

/** {@code /console/api/templates*} — ADMIN and TEMPLATE_EDITOR (T135). */
@RestController
@RequestMapping("/console/api/templates")
@PreAuthorize("hasAnyRole('ADMIN','TEMPLATE_EDITOR')")
class TemplateConsoleController {

    record PreviewRequest(
            @NotBlank String channelType,
            String subjectTemplate,
            String titleTemplate,
            @NotBlank String bodyTemplate,
            Map<String, String> variables) {}

    private final TemplateCommandService commands;
    private final TemplatePreviewService previews;

    TemplateConsoleController(TemplateCommandService commands, TemplatePreviewService previews) {
        this.commands = commands;
        this.previews = previews;
    }

    @GetMapping
    TemplateCommandService.PageResult list(
            @RequestParam(required = false) String q,
            @RequestParam(name = "channel_type", required = false) String channelType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return commands.list(q, channelType, status, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TemplateView create(@Valid @RequestBody TemplateCreateInput input) {
        return commands.create(input.toInput());
    }

    @GetMapping("/{code}")
    TemplateView get(@PathVariable String code) {
        return commands.get(code);
    }

    @PutMapping("/{code}")
    TemplateView update(@PathVariable String code, @Valid @RequestBody TemplateInput input) {
        return commands.update(code, input);
    }

    @DeleteMapping("/{code}")
    TemplateView deactivate(@PathVariable String code, @RequestParam long version) {
        return commands.setActive(code, version, false);
    }

    @PostMapping("/{code}/activate")
    TemplateView activate(@PathVariable String code, @RequestParam long version) {
        return commands.setActive(code, version, true);
    }

    @PostMapping("/preview")
    TemplatePreviewService.Preview preview(@Valid @RequestBody PreviewRequest req) {
        return previews.preview(
                req.channelType(), req.subjectTemplate(), req.titleTemplate(), req.bodyTemplate(), req.variables());
    }
}
