package com.pnh.template;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Published API of the template module (T084). */
public interface TemplateApi {

    /** Template by code, active or not (intake decides TEMPLATE_INACTIVE). */
    Optional<TemplateInfo> find(String templateCode);

    /** Required variables absent (or blank) in {@code variables}. */
    List<String> missingVariables(TemplateInfo template, Map<String, String> variables);

    /** Renders the given template version (rendered at acceptance, research R8 / fix I1). */
    RenderedContent render(TemplateInfo template, Map<String, String> variables);
}
