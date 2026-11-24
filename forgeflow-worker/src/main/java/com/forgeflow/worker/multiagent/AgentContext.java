package com.forgeflow.worker.multiagent;

import com.forgeflow.worker.context.AgentMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-subtask conversation. No shared mutable message list between agents:
 * context interference is prevented by construction.
 */
public final class AgentContext {

    private final List<AgentMessage> messages = new ArrayList<>();

    public synchronized void append(AgentMessage message) {
        messages.add(message);
    }

    public synchronized List<AgentMessage> snapshot() {
        return List.copyOf(messages);
    }

    public synchronized int size() {
        return messages.size();
    }
}
