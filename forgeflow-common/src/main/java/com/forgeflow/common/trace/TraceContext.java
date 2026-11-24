package com.forgeflow.common.trace;

/**
 * W3C-style trace context. 32 hex chars for the trace, 16 for the span.
 */
public record TraceContext(String traceId, String spanId, String parentSpanId) {

    public static TraceContext root(String traceId, String spanId) {
        return new TraceContext(traceId, spanId, null);
    }

    public TraceContext child(String childSpanId) {
        return new TraceContext(traceId, childSpanId, spanId);
    }
}
