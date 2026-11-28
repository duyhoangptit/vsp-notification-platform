package com.vsp.notification.template.adapter.out;

import com.vsp.notification.template.domain.Template;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TemplateRepository extends JpaRepository<Template, String>, JpaSpecificationExecutor<Template> {}
