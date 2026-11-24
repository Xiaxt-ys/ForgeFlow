package com.forgeflow.worker.model;

import com.forgeflow.common.NoAvailableModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tries candidates in order. A first-packet timeout is treated as a model failure, not as a
 * user-visible error; the next candidate takes over.
 */
public class FirstPacketModelRouter {

    private static final Logger log = LoggerFactory.getLogger(FirstPacketModelRouter.class);

    private final List<ModelClient> candidates;
    private final ModelHealthStore health;
    private final Duration firstPacketTimeout;

    public FirstPacketModelRouter(List<ModelClient> candidates, ModelHealthStore health,
                                  Duration firstPacketTimeout) {
        this.candidates = List.copyOf(candidates);
        this.health = health;
        this.firstPacketTimeout = firstPacketTimeout;
    }

    public String complete(String prompt) {
        Throwable last = null;
        for (ModelClient client : candidates) {
            ModelHealthStore.Permit permit = health.allow(client.id());
            if (permit == null) {
                continue;
            }
            try {
                String result = client.complete(prompt)
                        .get(firstPacketTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (result == null || result.isBlank()) {
                    throw new IllegalStateException("empty first packet from " + client.id());
                }
                health.success(client.id());
                return result;
            } catch (TimeoutException e) {
                last = e;
                health.failure(client.id());
                log.warn("first packet timeout, fallback. model={} timeout={}ms",
                        client.id(), firstPacketTimeout.toMillis());
            } catch (ExecutionException e) {
                last = e.getCause() == null ? e : e.getCause();
                health.failure(client.id());
                log.warn("model failed, fallback. model={}", client.id(), last);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                health.releaseHalfOpen(permit);
                throw new NoAvailableModelException("interrupted while calling model", e);
            } catch (RuntimeException e) {
                last = e;
                health.failure(client.id());
            }
        }
        throw new NoAvailableModelException("all model candidates failed", last);
    }
}
