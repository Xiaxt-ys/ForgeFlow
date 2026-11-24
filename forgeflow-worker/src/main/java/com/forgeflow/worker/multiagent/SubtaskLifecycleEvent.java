package com.forgeflow.worker.multiagent;

public record SubtaskLifecycleEvent(String subtaskId, String status) implements AgentEvent {
}
