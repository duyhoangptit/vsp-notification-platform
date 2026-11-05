package com.pnh.template.domain;

import com.pnh.shared.audit.AuditableConfigEntity;
import com.pnh.shared.domain.Category;
import com.pnh.shared.domain.RecordStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.regex.Pattern;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Notification template (data-model: pnh_templates). */
@Entity
@Table(name = "pnh_templates")
public class Template extends AuditableConfigEntity {

    public static final Pattern CODE = Pattern.compile("^[A-Z0-9_]{3,100}$");

    @Id
    @Column(name = "template_code")
    private String templateCode;

    @Column(name = "template_name", nullable = false)
    private String templateName;

    @Column(name = "channel_type", nullable = false)
    private String channelType;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private Category category;

    @Column(name = "is_critical", nullable = false)
    private boolean critical;

    @Column(name = "otp_variable")
    private String otpVariable;

    @Column(name = "expiry_variable")
    private String expiryVariable;

    @Column(name = "subject_template")
    private String subjectTemplate;

    @Column(name = "title_template")
    private String titleTemplate;

    @Column(name = "body_template", nullable = false)
    private String bodyTemplate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "external_template_ref", columnDefinition = "jsonb")
    private String externalTemplateRef;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "required_variables", nullable = false, columnDefinition = "text[]")
    private List<String> requiredVariables;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RecordStatus status;

    protected Template() {}

    public Template(String templateCode) {
        if (templateCode == null || !CODE.matcher(templateCode).matches()) {
            throw new IllegalArgumentException("template_code must match " + CODE.pattern());
        }
        this.templateCode = templateCode;
        this.status = RecordStatus.ACTIVE;
        this.category = Category.STANDARD;
        this.requiredVariables = List.of();
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getChannelType() {
        return channelType;
    }

    public Category getCategory() {
        return category;
    }

    public boolean isCritical() {
        return critical;
    }

    public String getOtpVariable() {
        return otpVariable;
    }

    public String getExpiryVariable() {
        return expiryVariable;
    }

    public String getSubjectTemplate() {
        return subjectTemplate;
    }

    public String getTitleTemplate() {
        return titleTemplate;
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public String getExternalTemplateRef() {
        return externalTemplateRef;
    }

    public List<String> getRequiredVariables() {
        return requiredVariables == null ? List.of() : List.copyOf(requiredVariables);
    }

    public RecordStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == RecordStatus.ACTIVE;
    }

    public void setTemplateName(String v) {
        this.templateName = v;
    }

    public void setChannelType(String v) {
        this.channelType = v;
    }

    public void setCategory(Category v) {
        this.category = v;
    }

    public void setCritical(boolean v) {
        this.critical = v;
    }

    public void setOtpVariable(String v) {
        this.otpVariable = v;
    }

    public void setExpiryVariable(String v) {
        this.expiryVariable = v;
    }

    public void setSubjectTemplate(String v) {
        this.subjectTemplate = v;
    }

    public void setTitleTemplate(String v) {
        this.titleTemplate = v;
    }

    public void setBodyTemplate(String v) {
        this.bodyTemplate = v;
    }

    public void setExternalTemplateRef(String v) {
        this.externalTemplateRef = v;
    }

    public void setRequiredVariables(List<String> v) {
        this.requiredVariables = List.copyOf(v);
    }

    public void activate() {
        this.status = RecordStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = RecordStatus.INACTIVE;
    }
}
