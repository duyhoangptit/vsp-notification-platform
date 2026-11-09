package com.pnh.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.pnh.support.IntegrationTestBase;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Keeps the code and the hand-written contracts (the single source of truth) in sync: the OpenAPI document springdoc
 * generates from the controllers must describe exactly the operations of {@code contracts/*.openapi.yaml}, with the
 * same query parameters and the same JSON field names/types in request and success-response bodies. Any drift, in
 * either direction, fails the build — fix the code or update the contract first.
 *
 * <p>Status codes, security and error bodies cannot be inferred from the controllers; they stay covered by the
 * per-endpoint contract tests ({@link ContractSupport#assertConforms}).
 */
class ApiContractDriftIT extends IntegrationTestBase {

    private static final List<String> CONTRACTS =
            List.of(ContractSupport.CLIENT_API, ContractSupport.CONSOLE_API, ContractSupport.CALLBACKS_API);

    private static final String DEFERRED = "x-pnh-deferred";

    /** Only the HTTP APIs are contract-bound (not the SPA forwards). */
    private static final List<String> API_PREFIXES = List.of("/api/", "/console/api/");

    @Test
    void codeMatchesContracts() throws Exception {
        String generatedJson =
                mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Operation> code = operations(parse(generatedJson, true));
        Map<String, Operation> contract = new TreeMap<>();
        for (String c : CONTRACTS) {
            contract.putAll(operations(parse(c, false)));
        }

        List<String> problems = new ArrayList<>();
        for (String op : diff(contract.keySet(), code.keySet())) {
            if (!deferred(contract.get(op))) {
                problems.add(op + ": in contract, not implemented");
            }
        }
        diff(code.keySet(), contract.keySet()).forEach(op -> problems.add(op + ": implemented, missing from contract"));

        for (String op : contract.keySet()) {
            Operation expected = contract.get(op);
            Operation actual = code.get(op);
            if (actual == null) {
                continue;
            }
            if (deferred(expected)) {
                problems.add(op + ": implemented, remove " + DEFERRED + " from the contract");
            }
            compareNames(problems, op + " query params", queryParams(expected), queryParams(actual));
            Schema<?> actualRequest = requestSchema(actual);
            if (!isRawBody(actualRequest)) { // signed callbacks read raw bytes; ProviderCallbackContractIT covers them
                compareFields(problems, op + " request body", requestSchema(expected), actualRequest);
            }
            compareFields(problems, op + " response body", successSchema(expected), successSchema(actual));
        }

        assertThat(problems)
                .as("Code drifted from contracts/*.openapi.yaml:%n  %s", String.join("\n  ", problems))
                .isEmpty();
    }

    // --- parsing ---------------------------------------------------------------------------------------------

    private static OpenAPI parse(String source, boolean inline) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        options.setResolveCombinators(true);
        SwaggerParseResult result = inline
                ? new OpenAPIV3Parser().readContents(source, null, options)
                : new OpenAPIV3Parser().readLocation(source, null, options);
        assertThat(result.getOpenAPI())
                .as("cannot parse %s: %s", inline ? "/v3/api-docs" : source, result.getMessages())
                .isNotNull();
        return result.getOpenAPI();
    }

    /** "METHOD /path/{}" → operation; path variable names are not part of the wire contract. */
    private static Map<String, Operation> operations(OpenAPI api) {
        Map<String, Operation> ops = new TreeMap<>();
        if (api.getPaths() == null) {
            return ops;
        }
        api.getPaths().forEach((path, item) -> {
            if (API_PREFIXES.stream().noneMatch(path::startsWith)) {
                return;
            }
            String normalized = path.replaceAll("\\{[^}]+}", "{}");
            for (Map.Entry<PathItem.HttpMethod, Operation> e :
                    item.readOperationsMap().entrySet()) {
                ops.put(e.getKey() + " " + normalized, e.getValue());
            }
        });
        return ops;
    }

    /** Contract operations marked {@code x-pnh-deferred: true} are designed but intentionally not built yet. */
    private static boolean deferred(Operation op) {
        return op.getExtensions() != null
                && Boolean.TRUE.equals(op.getExtensions().get(DEFERRED));
    }

    /** A {@code byte[]}/{@code String} body (string, binary) — the handler parses it itself. */
    private static boolean isRawBody(Schema<?> s) {
        return s != null && "string".equals(type(s));
    }

    private static Set<String> queryParams(Operation op) {
        Set<String> names = new TreeSet<>();
        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) {
                if ("query".equals(p.getIn())) {
                    names.add(p.getName());
                }
            }
        }
        return names;
    }

    private static Schema<?> requestSchema(Operation op) {
        return op.getRequestBody() == null
                ? null
                : jsonSchema(op.getRequestBody().getContent());
    }

    /** Body of the lowest 2xx response that has one. */
    private static Schema<?> successSchema(Operation op) {
        if (op.getResponses() == null) {
            return null;
        }
        return op.getResponses().entrySet().stream()
                .filter(e -> e.getKey().startsWith("2"))
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .map(ApiResponse::getContent)
                .map(ApiContractDriftIT::jsonSchema)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static Schema<?> jsonSchema(Content content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        return content.entrySet().stream()
                .filter(e -> e.getKey().contains("json") || e.getKey().equals("*/*"))
                .map(Map.Entry::getValue)
                .map(MediaType::getSchema)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // --- comparison -------------------------------------------------------------------------------------------

    private static void compareNames(List<String> problems, String what, Set<String> expected, Set<String> actual) {
        diff(expected, actual).forEach(n -> problems.add(what + ": '" + n + "' in contract, not in code"));
        diff(actual, expected).forEach(n -> problems.add(what + ": '" + n + "' in code, not in contract"));
    }

    private static void compareFields(List<String> problems, String what, Schema<?> expected, Schema<?> actual) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected == null || actual == null) {
            problems.add(what
                    + (expected == null
                            ? ": code has a body, contract has none"
                            : ": contract has a body, code has none"));
            return;
        }
        Map<String, String> want = new TreeMap<>();
        Map<String, String> got = new TreeMap<>();
        flatten(expected, "$", want, 0);
        flatten(actual, "$", got, 0);
        compareNames(problems, what, want.keySet(), got.keySet());
        for (String field : want.keySet()) {
            String w = want.get(field);
            String g = got.get(field);
            if (g != null && !w.equals(g) && !"any".equals(w) && !"any".equals(g)) {
                problems.add(what + ": '" + field + "' is " + g + " in code, " + w + " in contract");
            }
        }
    }

    /** Collects "$.a.b[].c" → JSON type for every field of the (fully resolved) schema. */
    private static void flatten(Schema<?> s, String prefix, Map<String, String> out, int depth) {
        if (s == null || depth > 10) {
            return;
        }
        List<Schema> variants = new ArrayList<>();
        if (s.getOneOf() != null) {
            variants.addAll(s.getOneOf());
        }
        if (s.getAnyOf() != null) {
            variants.addAll(s.getAnyOf());
        }
        if (s.getAllOf() != null) {
            variants.addAll(s.getAllOf());
        }
        for (Schema<?> v : variants) {
            flatten(v, prefix, out, depth + 1);
        }
        if ("array".equals(type(s))) {
            flatten(s.getItems(), prefix + "[]", out, depth + 1);
        }
        if (s.getProperties() != null) {
            s.getProperties().forEach((name, prop) -> {
                String field = prefix + "." + name;
                out.put(field, type(prop));
                flatten(prop, field, out, depth + 1);
            });
        }
    }

    private static String type(Schema<?> s) {
        if (s.getType() != null) {
            return s.getType();
        }
        if (s.getTypes() != null) {
            return s.getTypes().stream()
                    .filter(t -> !"null".equals(t))
                    .findFirst()
                    .orElse("any");
        }
        return s.getProperties() != null ? "object" : "any";
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> d = new TreeSet<>(a);
        d.removeAll(b);
        return d;
    }
}
