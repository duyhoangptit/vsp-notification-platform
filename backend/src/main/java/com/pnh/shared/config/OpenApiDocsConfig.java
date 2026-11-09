package com.pnh.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the hand-written OpenAPI contracts (copied to {@code classpath:/openapi} at build time) under
 * {@code /openapi/**} so Swagger UI ({@code /swagger-ui.html}) can render them. Only active when API docs are enabled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
class OpenApiDocsConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/openapi/**").addResourceLocations("classpath:/openapi/");
    }
}
