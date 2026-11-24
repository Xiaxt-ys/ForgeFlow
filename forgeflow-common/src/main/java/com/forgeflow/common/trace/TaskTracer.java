package com.forgeflow.common.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight trace layer:
 * - one traceId per task, propagated through the queue message;
 * - one span per boundary (submit/claim/lease/agent/commit);
 * - every span start/end is logged with traceId and spanId.
 *
 * This is deliberately dependency-free so it works in local mode and tests.
 * The same IDs can later be exported to OTLP by a bridge without changing call sites.
 */
public class TaskTracer {

    private static final Logger log = LoggerFactory.getLogger(TaskTracer.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

    public TraceContext newTrace() {
        return TraceContext.root(hex(16), hex(8));
    }

    public TraceContext child(TraceContext parent) {
        return parent == null ? newTrace() : parent.child(hex(8));
    }

    public String encode(TraceContext context) {
        if (context == null) {
            return null;
        }
        return context.traceId() + "-" + context.spanId();
    }

    public TraceContext decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split("-");
        if (parts.length != 2 || parts[0].length() != 32 || parts[1].length() != 16) {
            return null;
        }
        return TraceContext.root(parts[0], parts[1]);
    }

    public Span start(String name, TraceContext context) {
        TraceContext previous = CURRENT.get();
        TraceContext effective = context == null ? newTrace() : context;
        CURRENT.set(effective);
        MDC.put("traceId", effective.traceId());
        MDC.put("spanId", effective.spanId());
        log.info("trace.start name={} traceId={} spanId={} parentSpanId={}",
                name, effective.traceId(), effective.spanId(), effective.parentSpanId());
        return new Span(this, name, effective, previous);
    }

    public TraceContext current() {
        return CURRENT.get();
    }

    public static final class Span implements AutoCloseable {

        private final TaskTracer owner;
        private final String name;
        private final TraceContext context;
        private final TraceContext previous;
        private final long startedNanos;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private String status = "OK";
        private boolean closed;

        private Span(TaskTracer owner, String name, TraceContext context, TraceContext previous) {
            this.owner = owner;
            this.name = name;
            this.context = context;
            this.previous = previous;
            this.startedNanos = System.nanoTime();
        }

        public TraceContext context() {
            return context;
        }

        public Span tag(String key, Object value) {
            if (key != null && value != null) {
                attributes.put(key, value);
            }
            return this;
        }

        public Span error(Throwable error) {
            status = "ERROR";
            if (error != null) {
                attributes.put("error", error.getClass().getSimpleName());
                attributes.put("errorMessage", error.getMessage());
            }
            return this;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            long durationMs = (System.nanoTime() - startedNanos) / 1_000_000;
            log.info("trace.end name={} traceId={} spanId={} durationMs={} status={} attributes={}",
                    name, context.traceId(), context.spanId(), durationMs, status, attributes);
            if (previous == null) {
                owner.restore(null);
            } else {
                owner.restore(previous);
            }
        }
    }

    private void restore(TraceContext context) {
        if (context == null) {
            CURRENT.remove();
            MDC.remove("traceId");
            MDC.remove("spanId");
            return;
        }
        CURRENT.set(context);
        MDC.put("traceId", context.traceId());
        MDC.put("spanId", context.spanId());
    }

    private static String hex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buffer) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
