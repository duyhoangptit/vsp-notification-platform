package com.pnh.template.application;

/** What to render: {@code html=true} renders the body in HTML mode (EMAIL); subject/title are always plain text. */
public record TemplateSource(String code, long version, boolean html, String subject, String title, String body) {}
