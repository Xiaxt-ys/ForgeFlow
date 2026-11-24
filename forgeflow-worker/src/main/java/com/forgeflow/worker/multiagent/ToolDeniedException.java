package com.forgeflow.worker.multiagent;

public class ToolDeniedException extends RuntimeException {
    public ToolDeniedException(String toolName) {
        super("tool denied: " + toolName);
    }
}
