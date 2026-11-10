package com.pnh.shared.web;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.logging.TraceIds;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** RFC 9457 error rendering for every REST endpoint (client API and console API). */
@RestControllerAdvice
public class GlobalProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalProblemHandler.class);

    @ExceptionHandler(PnhException.class)
    public ResponseEntity<Map<String, Object>> handlePnh(PnhException e) {
        ProblemDetail pd = ProblemFactory.withTraceId(ProblemFactory.from(e), TraceIds.current());
        logFailure(pd, e);
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        Long retryAfter = e.retryAfterSeconds();
        if (retryAfter != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        }
        return builder.body(ProblemFactory.toMap(pd));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleInvalid(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(snake(fe.getField()), fe.getDefaultMessage()))
                .toList();
        return respond(ProblemFactory.validation(errors), e);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleMethodValidation(HandlerMethodValidationException e) {
        List<FieldError> errors = e.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> new FieldError(r.getMethodParameter().getParameterName(), err.getDefaultMessage())))
                .toList();
        return respond(ProblemFactory.validation(errors), e);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException e) {
        List<FieldError> errors = e.getConstraintViolations().stream()
                .map(v -> new FieldError(snake(lastNode(v.getPropertyPath().toString())), v.getMessage()))
                .toList();
        return respond(ProblemFactory.validation(errors), e);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        MissingRequestHeaderException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        String detail = e instanceof MissingRequestHeaderException m
                ? "Missing header " + m.getHeaderName()
                : e instanceof MissingServletRequestParameterException p
                        ? "Missing parameter " + p.getParameterName()
                        : e instanceof MethodArgumentTypeMismatchException t
                                ? "Invalid value for " + t.getName()
                                : "Malformed request body";
        return respond(ProblemFactory.problem(ErrorCode.VALIDATION_ERROR, 400, detail), e);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return respond(ProblemFactory.problem(ErrorCode.VERSION_CONFLICT, 409, ErrorCode.VERSION_CONFLICT.title()), e);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<Map<String, Object>> handleDenied(Exception e) {
        return respond(ProblemFactory.problem(ErrorCode.FORBIDDEN, 403, ErrorCode.FORBIDDEN.title()), e);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        return respond(ProblemFactory.problem(ErrorCode.NOT_FOUND, 404, "Not found"), e);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethod(HttpRequestMethodNotSupportedException e) {
        return respond(ProblemFactory.problem(ErrorCode.VALIDATION_ERROR, 405, "Method not allowed"), e);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(ProblemFactory.problem(ErrorCode.VALIDATION_ERROR, 415, "Unsupported media type"), e);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unexpected error [{}] on {}", e.getClass().getSimpleName(), currentRequest(), e);
        return respond(ProblemFactory.problem(ErrorCode.INTERNAL_ERROR, 500, "An unexpected error occurred"));
    }

    /** Logs the handled exception, then renders it. */
    private static ResponseEntity<Map<String, Object>> respond(ProblemDetail pd, Exception e) {
        logFailure(pd, e);
        return respond(pd);
    }

    /**
     * 4xx: one WARN line (client mistakes are expected, no stack trace); 5xx: ERROR with stack trace. Field errors are
     * logged instead of the raw validation message, which would echo every rejected value.
     */
    private static void logFailure(ProblemDetail pd, Exception e) {
        Map<String, Object> props = pd.getProperties() == null ? Map.of() : pd.getProperties();
        Object errors = props.get("errors");
        String detail = errors != null
                ? pd.getDetail() + " " + errors
                : java.util.Objects.equals(pd.getDetail(), e.getMessage())
                        ? pd.getDetail()
                        : pd.getDetail() + " (" + e.getMessage() + ")";
        if (pd.getStatus() >= 500) {
            log.error(
                    "Request failed {} [{}] on {}: {}", pd.getStatus(), props.get("code"), currentRequest(), detail, e);
        } else {
            log.warn(
                    "Request rejected {} [{}] on {}: {} <{}>",
                    pd.getStatus(),
                    props.get("code"),
                    currentRequest(),
                    detail,
                    e.getClass().getSimpleName());
        }
    }

    private static String currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getMethod() + " " + attrs.getRequest().getRequestURI();
        }
        return "-";
    }

    private static ResponseEntity<Map<String, Object>> respond(ProblemDetail pd) {
        ProblemFactory.withTraceId(pd, TraceIds.current());
        return ResponseEntity.status(pd.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemFactory.toMap(pd));
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }
}
