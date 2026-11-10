package com.pnh.shared.web;

import com.pnh.shared.domain.ErrorCode;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds problem documents; shared by MVC handlers and security entry points. */
public final class ProblemFactory {

    public static final String TYPE_BASE = "https://pnh.example.com/problems/";
    public static final String TRACE_ID = "trace_id";

    private ProblemFactory() {}

    public static ProblemDetail problem(ErrorCode code, int status, String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatusCode.valueOf(status));
        pd.setType(URI.create(TYPE_BASE + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        pd.setTitle(code.title());
        if (detail != null) {
            pd.setDetail(detail);
        }
        pd.setProperty("code", code.name());
        return pd;
    }

    public static ProblemDetail from(PnhException e) {
        ProblemDetail pd = problem(e.code(), e.status(), e.getMessage());
        if (!e.fieldErrors().isEmpty()) {
            pd.setProperty("errors", e.fieldErrors());
        }
        for (Map.Entry<String, Object> p : e.properties().entrySet()) {
            pd.setProperty(p.getKey(), p.getValue());
        }
        return pd;
    }

    public static ProblemDetail validation(List<FieldError> errors) {
        ProblemDetail pd = problem(ErrorCode.VALIDATION_ERROR, 422, "Request validation failed");
        pd.setProperty("errors", errors);
        return pd;
    }

    /**
     * Problem body as an ordered map: avoids the framework adding {@code instance}, which the contracts do not define.
     */
    public static Map<String, Object> toMap(ProblemDetail pd) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", String.valueOf(pd.getType()));
        m.put("title", pd.getTitle());
        m.put("status", pd.getStatus());
        if (pd.getDetail() != null) {
            m.put("detail", pd.getDetail());
        }
        if (pd.getProperties() != null) {
            m.putAll(pd.getProperties());
        }
        return m;
    }

    /** Adds {@code trace_id} (see {@link com.pnh.shared.logging.TraceIds}) when the request has one. */
    public static ProblemDetail withTraceId(ProblemDetail pd, String traceId) {
        if (traceId != null) {
            pd.setProperty(TRACE_ID, traceId);
        }
        return pd;
    }

    /** Minimal JSON rendering for filters that run before the MVC message converters. */
    public static String toJson(ErrorCode code, int status, String detail, String traceId) {
        return "{\"type\":\"" + TYPE_BASE + code.name().toLowerCase(Locale.ROOT).replace('_', '-') + "\",\"title\":\""
                + code.title() + "\",\"status\":" + status + ",\"code\":\"" + code.name() + "\""
                + (detail == null
                        ? ""
                        : ",\"detail\":\"" + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                + (traceId == null ? "" : ",\"" + TRACE_ID + "\":\"" + traceId + "\"") + "}";
    }
}
