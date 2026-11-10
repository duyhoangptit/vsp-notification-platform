package com.pnh.shared.logging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The trace id of the current HTTP request, as assigned by {@link CorrelationFilter}. It is returned to callers in the
 * {@value #HEADER} header and in the {@code trace_id} field of problem documents, so an error seen by a client can be
 * looked up in the logs ({@code traceId} MDC key) and in the tracing backend.
 */
public final class TraceIds {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    static final String ATTRIBUTE = TraceIds.class.getName();

    private TraceIds() {}

    /** Trace id of the given request, or {@code null} when it did not pass through {@link CorrelationFilter}. */
    public static String of(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String id ? id : null;
    }

    /** Trace id of the request bound to the current thread, or {@code null} outside a request. */
    public static String current() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? of(attrs.getRequest())
                : null;
    }
}
