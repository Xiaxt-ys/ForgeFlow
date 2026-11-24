package com.forgeflow.worker.model;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.Prompt;
import com.forgeflow.common.NoAvailableModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ChatModel wrapper that applies candidate routing, first-packet timeout and the
 * three-state breaker to every model call made by AgentRunner.
 */
public class RoutingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RoutingChatModel.class);

    public record Candidate(String id, ChatModel model) {
    }

    private final List<Candidate> candidates;
    private final ModelHealthStore health;
    private final Duration firstPacketTimeout;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "ff-model-call");
        thread.setDaemon(true);
        return thread;
    });

    public RoutingChatModel(List<Candidate> candidates, ModelHealthStore health,
                            Duration firstPacketTimeout) {
        this.candidates = List.copyOf(candidates);
        this.health = health;
        this.firstPacketTimeout = firstPacketTimeout;
    }

    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        Throwable last = null;
        for (Candidate candidate : candidates) {
            ModelHealthStore.Permit permit = health.allow(candidate.id());
            if (permit == null) {
                log.warn("model {} skipped: breaker is open", candidate.id());
                continue;
            }
            Future<AiMessageResponse> future = executor.submit(
                    () -> candidate.model().chat(prompt, options));
            try {
                AiMessageResponse response = future.get(
                        firstPacketTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (response == null) {
                    throw new IllegalStateException("empty response from " + candidate.id());
                }
                health.success(candidate.id());
                return response;
            } catch (TimeoutException timeout) {
                future.cancel(true);
                last = timeout;
                health.failure(candidate.id());
                log.warn("model {} first-packet timeout after {}ms, trying next candidate",
                        candidate.id(), firstPacketTimeout.toMillis());
            } catch (ExecutionException failure) {
                last = failure.getCause() == null ? failure : failure.getCause();
                health.failure(candidate.id());
                log.warn("model {} failed, trying next candidate: {}",
                        candidate.id(), last.getMessage());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                health.releaseHalfOpen(permit);
                throw new NoAvailableModelException("interrupted while calling model", interrupted);
            } catch (RuntimeException runtime) {
                last = runtime;
                health.failure(candidate.id());
            }
        }
        throw new NoAvailableModelException("all model candidates failed", last);
    }

    @Override
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        // Streaming fallback is intentionally delegated to the first healthy candidate.
        // Non-streaming model calls (the Agent tool loop) get full fallback above.
        for (Candidate candidate : candidates) {
            if (health.allow(candidate.id()) != null) {
                candidate.model().chatStream(prompt, listener, options);
                return;
            }
        }
        throw new NoAvailableModelException("no healthy model candidate for streaming");
    }

}
