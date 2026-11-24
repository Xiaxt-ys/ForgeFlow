package com.forgeflow.worker.multiagent;

public record ToolResult(String output, boolean success) {
    public static ToolResult ok(String output) {
        return new ToolResult(output, true);
    }
}
