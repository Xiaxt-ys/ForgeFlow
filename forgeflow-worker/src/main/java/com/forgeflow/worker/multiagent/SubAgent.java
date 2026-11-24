package com.forgeflow.worker.multiagent;

public interface SubAgent {
    SubtaskResult execute(Subtask subtask, AgentContext context, ToolRouter router,
                          AgentEventBus events);
}
