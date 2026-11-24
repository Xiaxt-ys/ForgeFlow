package com.forgeflow.worker.tool;

public record ToolSpec(String name, ToolCapability capability, boolean needsConfirm,
                       String requiredSkill) {
    public static ToolSpec read(String name) {
        return new ToolSpec(name, ToolCapability.READ, false, null);
    }

    public static ToolSpec write(String name) {
        return new ToolSpec(name, ToolCapability.WRITE, true, null);
    }
}
