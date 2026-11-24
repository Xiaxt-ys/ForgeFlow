package com.forgeflow.common;

/**
 * The single extension point the worker uses to run an agent.
 */
public interface AgentExecutor {
    AgentResult execute(AgentRequest request, ExecutionContext context) throws Exception;
}
