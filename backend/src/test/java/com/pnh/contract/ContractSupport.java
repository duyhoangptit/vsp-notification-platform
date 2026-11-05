package com.pnh.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/** Validates MockMvc exchanges against contracts/*.openapi.yaml and Kafka payloads against JSON schemas (T012). */
public final class ContractSupport {

    public static final String CLIENT_API = "contracts/client-api.openapi.yaml";
    public static final String CONSOLE_API = "contracts/console-api.openapi.yaml";
    public static final String CALLBACKS_API = "contracts/provider-callbacks.openapi.yaml";

    private static final Map<String, OpenApiInteractionValidator> VALIDATORS = new ConcurrentHashMap<>();
    private static final Map<String, JsonSchema> SCHEMAS = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContractSupport() {}

    private static OpenApiInteractionValidator validator(String spec) {
        return VALIDATORS.computeIfAbsent(
                spec,
                s -> OpenApiInteractionValidator.createFor(s)
                        .withLevelResolver(LevelResolver.create()
                                // session-cookie / basic auth are exercised by the security tests, not by schema
                                // validation
                                .withLevel(
                                        "validation.request.security.missing",
                                        com.atlassian.oai.validator.report.ValidationReport.Level.IGNORE)
                                .withLevel(
                                        "validation.request.security.invalid",
                                        com.atlassian.oai.validator.report.ValidationReport.Level.IGNORE)
                                // the contracts never declare additionalProperties:false; the validator's strict
                                // default would make
                                // every allOf composition (e.g. Template = TemplateInput + Audit) unsatisfiable
                                .withLevel(
                                        "validation.schema.additionalProperties",
                                        com.atlassian.oai.validator.report.ValidationReport.Level.IGNORE)
                                .build())
                        .build());
    }

    /** Asserts that both the request and the response of {@code result} conform to the OpenAPI document. */
    public static void assertConforms(String spec, MvcResult result) {
        MockHttpServletRequest req = result.getRequest();
        MockHttpServletResponse res = result.getResponse();
        SimpleRequest.Builder rb =
                new SimpleRequest.Builder(Request.Method.valueOf(req.getMethod()), req.getRequestURI());
        if (req.getQueryString() != null) {
            for (String pair : req.getQueryString().split("&")) {
                String[] kv = pair.split("=", 2);
                rb.withQueryParam(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
            }
        }
        for (String name : Collections.list(req.getHeaderNames())) {
            rb.withHeader(name, Collections.list(req.getHeaders(name)));
        }
        byte[] body = req.getContentAsByteArray();
        if (body != null && body.length > 0) {
            rb.withBody(new String(body, StandardCharsets.UTF_8));
        }
        SimpleResponse.Builder sb = SimpleResponse.Builder.status(res.getStatus());
        for (String name : res.getHeaderNames()) {
            sb.withHeader(name, res.getHeaders(name).toArray(String[]::new));
        }
        try {
            String content = res.getContentAsString(StandardCharsets.UTF_8);
            if (!content.isEmpty()) {
                sb.withBody(content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ValidationReport report = validator(spec).validate(rb.build(), sb.build());
        assertThat(report.getMessages())
                .as(
                        "OpenAPI contract %s violated by %s %s -> %d: %s",
                        spec, req.getMethod(), req.getRequestURI(), res.getStatus(), report.getMessages())
                .isEmpty();
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /** Validates a JSON document against contracts/kafka/{schema}; returns the violation messages. */
    public static Set<String> schemaViolations(String schemaFile, String json) {
        JsonSchema schema = SCHEMAS.computeIfAbsent(schemaFile, f -> {
            try (InputStream in = ContractSupport.class.getClassLoader().getResourceAsStream("contracts/kafka/" + f)) {
                SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                        .formatAssertionsEnabled(true)
                        .build();
                return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                        .getSchema(in, config);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            JsonNode node = MAPPER.readTree(json);
            return schema.validate(node).stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void assertMatchesSchema(String schemaFile, String json) {
        assertThat(schemaViolations(schemaFile, json))
                .as("JSON schema %s: %s", schemaFile, json)
                .isEmpty();
    }
}
