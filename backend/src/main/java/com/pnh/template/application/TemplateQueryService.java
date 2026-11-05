package com.pnh.template.application;

import com.pnh.shared.infra.CacheNames;
import com.pnh.template.RenderedContent;
import com.pnh.template.TemplateApi;
import com.pnh.template.TemplateInfo;
import com.pnh.template.adapter.out.TemplateRepository;
import com.pnh.template.domain.Template;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link TemplateApi}: lookups cached in {@code templateByCode} (5 min, evicted on change). */
@Service
public class TemplateQueryService implements TemplateApi {

    private final TemplateRepository repository;
    private final TemplateRenderer renderer;
    private final Cache cache;

    public TemplateQueryService(TemplateRepository repository, TemplateRenderer renderer, CacheManager cacheManager) {
        this.repository = repository;
        this.renderer = renderer;
        this.cache = cacheManager.getCache(CacheNames.TEMPLATE_BY_CODE);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TemplateInfo> find(String templateCode) {
        if (templateCode == null) {
            return Optional.empty();
        }
        TemplateInfo cached = cache.get(templateCode, TemplateInfo.class);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<TemplateInfo> info = repository.findById(templateCode).map(TemplateQueryService::toInfo);
        info.ifPresent(i -> cache.put(templateCode, i));
        return info;
    }

    @Override
    public List<String> missingVariables(TemplateInfo template, Map<String, String> variables) {
        Map<String, String> vars = variables == null ? Map.of() : variables;
        return template.requiredVariables().stream()
                .filter(v -> vars.get(v) == null || vars.get(v).isBlank())
                .toList();
    }

    @Override
    public RenderedContent render(TemplateInfo t, Map<String, String> variables) {
        return renderer.render(
                new TemplateSource(
                        t.templateCode(),
                        t.version(),
                        "EMAIL".equals(t.channelType()),
                        t.subjectTemplate(),
                        t.titleTemplate(),
                        t.bodyTemplate()),
                variables == null ? Map.of() : variables);
    }

    static TemplateInfo toInfo(Template t) {
        return new TemplateInfo(
                t.getTemplateCode(),
                t.getChannelType(),
                t.getCategory().name(),
                t.isCritical(),
                t.getOtpVariable(),
                t.getExpiryVariable(),
                t.getSubjectTemplate(),
                t.getTitleTemplate(),
                t.getBodyTemplate(),
                t.getRequiredVariables(),
                t.isActive(),
                t.getVersion(),
                t.getExternalTemplateRef());
    }
}
