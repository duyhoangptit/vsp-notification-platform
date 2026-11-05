package com.pnh.shared.web;

import com.pnh.shared.domain.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business error rendered as RFC 9457 Problem Details with a stable {@code code}. */
public class PnhException extends RuntimeException {

    private final ErrorCode code;
    private final int status;
    private final List<FieldError> fieldErrors;
    private final Map<String, Object> properties;
    private Long retryAfterSeconds;

    public PnhException(ErrorCode code, String detail) {
        this(code, code.httpStatus(), detail, List.of(), Map.of());
    }

    public PnhException(
            ErrorCode code, int status, String detail, List<FieldError> fieldErrors, Map<String, Object> properties) {
        super(detail == null ? code.title() : detail);
        this.code = code;
        this.status = status;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.properties = new LinkedHashMap<>(properties);
    }

    public static PnhException of(ErrorCode code) {
        return new PnhException(code, code.title());
    }

    public static PnhException field(ErrorCode code, String field, String message) {
        return new PnhException(code, code.httpStatus(), message, List.of(new FieldError(field, message)), Map.of());
    }

    public static PnhException missingVariables(List<String> names) {
        return new PnhException(
                ErrorCode.MISSING_VARIABLES,
                ErrorCode.MISSING_VARIABLES.httpStatus(),
                "Missing template variables: " + String.join(", ", names),
                List.of(),
                Map.of("missing_variables", List.copyOf(names)));
    }

    public static PnhException versionConflict(Object current) {
        return new PnhException(
                ErrorCode.VERSION_CONFLICT,
                409,
                ErrorCode.VERSION_CONFLICT.title(),
                List.of(),
                current == null ? Map.of() : Map.of("current", current));
    }

    public static PnhException rateLimited(long retryAfterSeconds) {
        PnhException e = new PnhException(ErrorCode.RATE_LIMITED, 429, "Rate limit exceeded", List.of(), Map.of());
        e.retryAfterSeconds = retryAfterSeconds;
        return e;
    }

    public ErrorCode code() {
        return code;
    }

    public int status() {
        return status;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    /** Seconds for the Retry-After header (429 only); not part of the problem body. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
