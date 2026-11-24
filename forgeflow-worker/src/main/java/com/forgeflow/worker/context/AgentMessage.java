package com.forgeflow.worker.context;

/**
 * Minimal message model for context governance. The worker can adapt agents-flex messages
 * into this model before trimming/compaction.
 */
public record AgentMessage(Role role, String content, String toolName) {

    public static AgentMessage system(String content) {
        return new AgentMessage(Role.SYSTEM, content, null);
    }

    public static AgentMessage user(String content) {
        return new AgentMessage(Role.USER, content, null);
    }

    public static AgentMessage assistant(String content) {
        return new AgentMessage(Role.ASSISTANT, content, null);
    }

    public static AgentMessage tool(String toolName, String content) {
        return new AgentMessage(Role.TOOL, content, toolName);
    }

    public int chars() {
        return content == null ? 0 : content.length();
    }
}
