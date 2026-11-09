package com.pnh.shared.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API docs (only when {@code springdoc.api-docs.enabled=true}).
 *
 * <ul>
 *   <li>Serves the hand-written OpenAPI contracts — the single source of truth, copied to {@code classpath:/openapi}
 *       at build time — under {@code /openapi/**}; Swagger UI ({@code /swagger-ui.html}) renders them.
 *   <li>The document springdoc generates from the controllers ({@code /v3/api-docs}) is not shown to readers; it
 *       exists so {@code ApiContractDriftIT} can fail the build when the code drifts from the contracts. Its models
 *       use the wire naming (snake_case), like the real JSON.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
class OpenApiDocsConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/openapi/**").addResourceLocations("classpath:/openapi/");
    }

    @Bean
    ModelResolver snakeCaseModelResolver(ObjectMapperProvider objectMapperProvider) {
        return new ModelResolver(objectMapperProvider
                .jsonMapper()
                .copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }
}
