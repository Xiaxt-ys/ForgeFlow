package com.forgeflow.common;

/**
 * Input handed to an agent executor after the worker has claimed the task.
 */
public record AgentRequest(String taskId, String tenantId, String prompt) {
}
