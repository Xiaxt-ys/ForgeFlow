package com.forgeflow.worker.tool;

import java.util.Map;

public class ToolGate {

    private final Map<String, ToolSpec> specs;
    private final ApprovalTokenService tokens;

    public ToolGate(Map<String, ToolSpec> specs, ApprovalTokenService tokens) {
        this.specs = Map.copyOf(specs);
        this.tokens = tokens;
    }

    public Decision check(ToolCall call, ApprovalToken approval) {
        ToolSpec spec = specs.get(call.toolName());
        if (spec == null) {
            return Decision.deny("unknown tool: " + call.toolName());
        }
        if (spec.capability() == ToolCapability.READ && !spec.needsConfirm()) {
            return Decision.allow("read-only tool");
        }
        if (!spec.needsConfirm()) {
            return Decision.allow("tool does not require confirmation");
        }
        if (approval == null) {
            return Decision.deny("approval required for " + call.toolName());
        }
        if (!tokens.verify(approval, call)) {
            return Decision.deny("approval invalid or args changed");
        }
        return Decision.allow("approved capability token");
    }

    public record Decision(boolean allowed, String reason) {
        static Decision allow(String reason) {
            return new Decision(true, reason);
        }

        static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }
}
