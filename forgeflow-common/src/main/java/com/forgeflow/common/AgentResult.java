package com.forgeflow.common;

import java.util.Map;

/**
 * Result of one agent execution. {@code metadata} is intentionally small; large artifacts belong in object storage.
 */
public record AgentResult(String output, long promptTokens, long completionTokens, int steps,
                          Map<String, Object> metadata) {
    public AgentResult(String output) {
        this(output, 0, 0, 1, Map.of());
    }
}
