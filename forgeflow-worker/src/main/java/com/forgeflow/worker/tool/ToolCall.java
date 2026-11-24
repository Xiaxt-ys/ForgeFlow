package com.forgeflow.worker.tool;

public record ToolCall(String taskId, String stepId, String toolName, String argsHash) {
}
