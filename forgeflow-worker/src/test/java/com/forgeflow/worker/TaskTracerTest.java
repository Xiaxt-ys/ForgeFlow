package com.forgeflow.worker;

import com.forgeflow.common.trace.TaskTracer;
import com.forgeflow.common.trace.TraceContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskTracerTest {

    private final TaskTracer tracer = new TaskTracer();

    @Test
    void encodeDecodeRoundTrip() {
        TraceContext root = tracer.newTrace();
        assertThat(tracer.decode(tracer.encode(root))).isEqualTo(root);
    }

    @Test
    void childKeepsTraceIdAndSetsParent() {
        TraceContext root = tracer.newTrace();
        TraceContext child = tracer.child(root);

        assertThat(child.traceId()).isEqualTo(root.traceId());
        assertThat(child.spanId()).isNotEqualTo(root.spanId());
        assertThat(child.parentSpanId()).isEqualTo(root.spanId());
    }

    @Test
    void spanSetsAndRestoresCurrentContext() {
        TraceContext root = tracer.newTrace();
        try (TaskTracer.Span span = tracer.start("test.span", root).tag("k", "v")) {
            assertThat(tracer.current()).isEqualTo(root);
            assertThat(span.context()).isEqualTo(root);
        }
        assertThat(tracer.current()).isNull();
    }

    @Test
    void invalidTraceStringIsRejected() {
        assertThat(tracer.decode("bad")).isNull();
        assertThat(tracer.decode(null)).isNull();
    }
}
