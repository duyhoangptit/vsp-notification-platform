package com.pnh.template.application;

import com.pnh.provider.ChannelCapabilities;
import com.pnh.provider.ChannelRegistry;
import com.pnh.shared.audit.ConfigChanged;
import com.pnh.shared.audit.PnhAuditorAware;
import com.pnh.shared.domain.Category;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.RecordStatus;
import com.pnh.shared.infra.CacheInvalidationBus;
import com.pnh.shared.infra.CacheNames;
import com.pnh.shared.web.HtmlSanitizerPolicy;
import com.pnh.shared.web.PnhException;
import com.pnh.template.adapter.in.web.dto.TemplateInput;
import com.pnh.template.adapter.in.web.dto.TemplateView;
import com.pnh.template.adapter.out.TemplateRepository;
import com.pnh.template.domain.PlaceholderParser;
import com.pnh.template.domain.Template;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Template authoring (T133, FR-020/021): placeholder validation, required-variable extraction, OTP rules, placeholder-safe
 * HTML sanitization (R28 fix H1), optimistic locking with 409 + current state, audit trail and cluster-wide cache eviction.
 */
@Service
public class TemplateCommandService {

    public record PageResult(List<TemplateView> items, long total) {}

    private final TemplateRepository repository;
    private final ChannelRegistry registry;
    private final ApplicationEventPublisher events;
    private final CacheInvalidationBus bus;
    private final JsonMapper json;
    private final Clock clock;

    public TemplateCommandService(
            TemplateRepository repository,
            ChannelRegistry registry,
            ApplicationEventPublisher events,
            CacheInvalidationBus bus,
            JsonMapper json,
            Clock clock) {
        this.repository = repository;
        this.registry = registry;
        this.events = events;
        this.bus = bus;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResult list(String q, String channel, String status, int page, int size) {
        Specification<Template> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(
                        cb.like(cb.lower(root.get("templateCode")), like),
                        cb.like(cb.lower(root.get("templateName")), like)));
            }
            if (channel != null && !channel.isBlank()) {
                p.add(cb.equal(root.get("channelType"), channel.toUpperCase(Locale.ROOT)));
            }
            if (status != null && !status.isBlank()) {
                p.add(cb.equal(root.get("status"), RecordStatus.valueOf(status)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        Page<Template> result = repository.findAll(
                spec, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), Sort.by("templateCode")));
        return new PageResult(result.map(this::view).getContent(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public TemplateView get(String code) {
        return view(load(code));
    }

    @Transactional
    public TemplateView create(TemplateInput in) {
        if (repository.existsById(in.templateCode())) {
            throw new PnhException(ErrorCode.TEMPLATE_CODE_EXISTS, "Template " + in.templateCode() + " already exists");
        }
        Template t = new Template(in.templateCode());
        apply(t, in);
        Template saved = repository.saveAndFlush(t);
        publish(saved, "CREATE", Map.of());
        return view(saved);
    }

    @Transactional
    public TemplateView update(String code, TemplateInput in) {
        Template t = load(code);
        checkVersion(t, in.version());
        Map<String, Object> before = snapshot(t);
        apply(t, in);
        Template saved = repository.saveAndFlush(t);
        publish(saved, "UPDATE", before);
        return view(saved);
    }

    @Transactional
    public TemplateView setActive(String code, long version, boolean active) {
        Template t = load(code);
        checkVersion(t, version);
        Map<String, Object> before = snapshot(t);
        if (active) {
            t.activate();
        } else {
            t.deactivate();
        }
        Template saved = repository.saveAndFlush(t);
        publish(saved, active ? "UPDATE" : "DEACTIVATE", before);
        return view(saved);
    }

    private Template load(String code) {
        return repository
                .findById(code)
                .orElseThrow(() -> new PnhException(ErrorCode.NOT_FOUND, "Template " + code + " not found"));
    }

    private void checkVersion(Template t, Long expected) {
        if (expected == null) {
            throw PnhException.field(ErrorCode.MISSING_FIELD, "version", "version is required");
        }
        if (t.isStale(expected)) {
            throw PnhException.versionConflict(view(t));
        }
    }

    private void apply(Template t, TemplateInput in) {
        ChannelType channel = ChannelType.parseOrNull(in.channelType());
        if (channel == null || !registry.isRegistered(channel)) {
            throw PnhException.field(
                    ErrorCode.UNSUPPORTED_CHANNEL, "channel_type", "Channel " + in.channelType() + " is not supported");
        }
        ChannelCapabilities caps = registry.capabilities(channel);
        Category category = Category.valueOf(in.category());
        boolean email = caps.supportsHtml();
        if (caps.requiresSubject() && blank(in.subjectTemplate())) {
            throw PnhException.field(
                    ErrorCode.MISSING_FIELD, "subject_template", "subject_template is required for " + channel);
        }
        if (caps.requiresTitle() && blank(in.titleTemplate())) {
            throw PnhException.field(
                    ErrorCode.MISSING_FIELD, "title_template", "title_template is required for " + channel);
        }
        if (caps.requiresExternalTemplate()
                && (in.externalTemplateRef() == null || in.externalTemplateRef().isEmpty())) {
            throw PnhException.field(
                    ErrorCode.MISSING_FIELD,
                    "external_template_ref",
                    "external_template_ref is required for " + channel);
        }
        String body = in.bodyTemplate();
        if (email) {
            TemplateRenderer.parseHtml(body); // rejects placeholders in non-URL/text attributes and th:* attributes
            body = HtmlSanitizerPolicy.sanitizeTemplate(body);
        }
        String subject = email ? emptyToNull(in.subjectTemplate()) : null;
        String title =
                caps.requiresTitle() || !blank(in.titleTemplate()) && !email ? emptyToNull(in.titleTemplate()) : null;
        List<String> vars = PlaceholderParser.variablesOf(subject, title, body);
        String otpVariable = null;
        String expiryVariable = null;
        if (category == Category.OTP) {
            otpVariable = blank(in.otpVariable()) ? "otp_code" : in.otpVariable();
            if (!PlaceholderParser.variables(body).contains(otpVariable)) {
                throw PnhException.field(
                        ErrorCode.INVALID_FIELD,
                        "otp_variable",
                        "OTP templates must contain ${" + otpVariable + "} in the body");
            }
            if (!blank(in.expiryVariable())) {
                if (!vars.contains(in.expiryVariable())) {
                    throw PnhException.field(
                            ErrorCode.INVALID_FIELD,
                            "expiry_variable",
                            "expiry_variable must be one of the template placeholders");
                }
                expiryVariable = in.expiryVariable();
            }
        }
        t.setTemplateName(in.templateName());
        t.setChannelType(channel.value());
        t.setCategory(category);
        t.setCritical(Boolean.TRUE.equals(in.isCritical()));
        t.setOtpVariable(otpVariable);
        t.setExpiryVariable(expiryVariable);
        t.setSubjectTemplate(subject);
        t.setTitleTemplate(title);
        t.setBodyTemplate(body);
        t.setExternalTemplateRef(
                in.externalTemplateRef() == null ? null : json.writeValueAsString(in.externalTemplateRef()));
        t.setRequiredVariables(vars);
    }

    private void publish(Template t, String action, Map<String, Object> before) {
        events.publishEvent(new ConfigChanged(
                "TEMPLATE",
                t.getTemplateCode(),
                action,
                t.getVersion(),
                before,
                snapshot(t),
                Set.of(),
                Set.of(),
                PnhAuditorAware.currentActor(),
                clock.instant()));
        bus.evict(CacheNames.TEMPLATE_BY_CODE, t.getTemplateCode());
    }

    private static Map<String, Object> snapshot(Template t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template_name", t.getTemplateName());
        m.put("channel_type", t.getChannelType());
        m.put("category", t.getCategory() == null ? null : t.getCategory().name());
        m.put("is_critical", t.isCritical());
        m.put("otp_variable", t.getOtpVariable());
        m.put("expiry_variable", t.getExpiryVariable());
        m.put("subject_template", t.getSubjectTemplate());
        m.put("title_template", t.getTitleTemplate());
        m.put("body_template", t.getBodyTemplate());
        m.put("external_template_ref", t.getExternalTemplateRef());
        m.put("status", t.getStatus() == null ? null : t.getStatus().name());
        m.values().removeIf(Objects::isNull);
        return m;
    }

    TemplateView view(Template t) {
        Map<String, Object> ext = t.getExternalTemplateRef() == null
                ? null
                : json.readValue(t.getExternalTemplateRef(), new TypeReference<Map<String, Object>>() {});
        return new TemplateView(
                t.getTemplateCode(),
                t.getTemplateName(),
                t.getChannelType(),
                t.getCategory().name(),
                t.isCritical(),
                t.getOtpVariable(),
                t.getExpiryVariable(),
                t.getSubjectTemplate(),
                t.getTitleTemplate(),
                t.getBodyTemplate(),
                ext,
                t.getStatus().name(),
                t.getRequiredVariables(),
                t.getCreatedAt(),
                t.getCreatedBy(),
                t.getUpdatedAt(),
                t.getUpdatedBy(),
                t.getVersion());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String emptyToNull(String s) {
        return blank(s) ? null : s;
    }
}
