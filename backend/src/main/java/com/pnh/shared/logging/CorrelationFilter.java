package com.pnh.shared.logging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-request correlation:
 *
 * <ul>
 *   <li>exposes the trace id in the {@value TraceIds#HEADER} response header (see {@link TraceIds});
 *   <li>writes one access-log line per API request (logger {@code pnh.access});
 *   <li>clears correlation MDC keys and the sensitive-value holder afterwards.
 * </ul>
 *
 * <p>Ordered right after Boot's {@code ServerHttpObservationFilter} ({@code HIGHEST_PRECEDENCE + 1}) so the HTTP span
 * is already current (trace id in MDC), yet before Spring Security, so 401/403 rejections are covered too. The query
 * string is never logged (it may carry recipients).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CorrelationFilter extends OncePerRequestFilter {

    private static final Logger access = LoggerFactory.getLogger("pnh.access");

    private final Supplier<String> traceIdSource;

    @Autowired
    public CorrelationFilter(ObjectProvider<Tracer> tracer) {
        this(() -> {
            Tracer t = tracer.getIfAvailable();
            Span span = t == null ? null : t.currentSpan();
            return span == null ? null : span.context().traceId();
        });
    }

    /** @param traceIdSource current trace id, or {@code null}/blank when there is no active span */
    public CorrelationFilter(Supplier<String> traceIdSource) {
        this.traceIdSource = traceIdSource;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        String traceId = traceIdSource.get();
        boolean generated = traceId == null || traceId.isBlank();
        if (generated) {
            // tracing disabled or no span: still hand out an id that matches the log lines of this request
            traceId = randomTraceId();
            MDC.put(TraceIds.MDC_KEY, traceId);
        }
        request.setAttribute(TraceIds.ATTRIBUTE, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            failed = true;
            throw e;
        } finally {
            try {
                logAccess(request, response, failed, (System.nanoTime() - start) / 1_000_000);
            } finally {
                if (generated) {
                    MDC.remove(TraceIds.MDC_KEY);
                }
                MDC.remove(MdcKeys.NOTIFICATION_ID);
                MDC.remove(MdcKeys.CLIENT_REQUEST_ID);
                MDC.remove(MdcKeys.CLIENT_ID);
                SensitiveValues.clear();
            }
        }
    }

    private static void logAccess(HttpServletRequest request, HttpServletResponse response, boolean failed, long ms) {
        String uri = request.getRequestURI();
        if (!isApi(uri)) {
            return; // actuator probes, SPA assets: too noisy and never carry business errors
        }
        // An exception escaping the chain is turned into a 500 by the container after this filter returns.
        int status = failed ? 500 : response.getStatus();
        if (status >= 500) {
            access.error("{} {} -> {} ({} ms)", request.getMethod(), uri, status, ms);
        } else if (status >= 400) {
            access.warn("{} {} -> {} ({} ms)", request.getMethod(), uri, status, ms);
        } else {
            access.info("{} {} -> {} ({} ms)", request.getMethod(), uri, status, ms);
        }
    }

    static boolean isApi(String uri) {
        return uri.startsWith("/api/") || uri.startsWith("/console/api/");
    }

    /** 32 lowercase hex chars, the W3C trace-id format. */
    private static String randomTraceId() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return HexFormat.of().toHexDigits(r.nextLong()) + HexFormat.of().toHexDigits(r.nextLong());
    }
}
