package com.forgeflow.worker.multiagent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Per-task event bus. Reasoning, tool and approval events are emitted by one
 * writer and fanned out to any number of subscribers (UI, JSONL trace, metrics).
 */
public final class AgentEventBus implements AutoCloseable {

    private final Sinks.Many<AgentEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(1024);

    public Flux<AgentEvent> flux() {
        return sink.asFlux();
    }

    public void emit(AgentEvent event) {
        // Events are best-effort for live consumers; the JSONL trace is the durable record.
        // Dropping under a slow/absent subscriber must not fail the agent.
        sink.tryEmitNext(event);
    }

    @Override
    public void close() {
        sink.tryEmitComplete();
    }
}
