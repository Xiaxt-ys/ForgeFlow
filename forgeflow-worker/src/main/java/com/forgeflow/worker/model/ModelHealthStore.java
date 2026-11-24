package com.forgeflow.worker.model;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Three-state breaker per model candidate. Half-open admits exactly one probe so a
 * recovering model is not hit by the whole herd.
 */
public class ModelHealthStore {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public record Permit(String modelId, long halfOpenToken) {
    }

    private final int failureThreshold;
    private final long openDurationMs;
    private final ConcurrentHashMap<String, Health> health = new ConcurrentHashMap<>();
    private final AtomicLong probeSeq = new AtomicLong();

    public ModelHealthStore(int failureThreshold, long openDurationMs) {
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;
    }

    public Permit allow(String modelId) {
        long now = System.currentTimeMillis();
        Health h = health.computeIfAbsent(modelId, id -> new Health());
        synchronized (h) {
            if (h.state == State.OPEN) {
                if (h.openUntil > now) {
                    return null;
                }
                h.state = State.HALF_OPEN;
                h.halfOpenInFlight = true;
                h.halfOpenToken = probeSeq.incrementAndGet();
                return new Permit(modelId, h.halfOpenToken);
            }
            if (h.state == State.HALF_OPEN) {
                if (h.halfOpenInFlight) {
                    return null;
                }
                h.halfOpenInFlight = true;
                h.halfOpenToken = probeSeq.incrementAndGet();
                return new Permit(modelId, h.halfOpenToken);
            }
            return new Permit(modelId, 0L);
        }
    }

    public void success(String modelId) {
        Health h = health.computeIfAbsent(modelId, id -> new Health());
        synchronized (h) {
            h.state = State.CLOSED;
            h.failures = 0;
            h.openUntil = 0;
            h.halfOpenInFlight = false;
        }
    }

    public void failure(String modelId) {
        long now = System.currentTimeMillis();
        Health h = health.computeIfAbsent(modelId, id -> new Health());
        synchronized (h) {
            if (h.state == State.HALF_OPEN) {
                open(h, now);
                return;
            }
            h.failures++;
            if (h.failures >= failureThreshold) {
                open(h, now);
            }
        }
    }

    public void releaseHalfOpen(Permit permit) {
        if (permit == null || permit.halfOpenToken() <= 0) {
            return;
        }
        Health h = health.get(permit.modelId());
        if (h == null) {
            return;
        }
        synchronized (h) {
            if (h.state == State.HALF_OPEN && h.halfOpenToken == permit.halfOpenToken()) {
                h.halfOpenInFlight = false;
            }
        }
    }

    public State state(String modelId) {
        Health h = health.get(modelId);
        return h == null ? State.CLOSED : h.state;
    }

    private void open(Health h, long now) {
        h.state = State.OPEN;
        h.openUntil = now + openDurationMs;
        h.failures = 0;
        h.halfOpenInFlight = false;
    }

    private static final class Health {
        int failures;
        long openUntil;
        boolean halfOpenInFlight;
        long halfOpenToken;
        State state = State.CLOSED;
    }
}
