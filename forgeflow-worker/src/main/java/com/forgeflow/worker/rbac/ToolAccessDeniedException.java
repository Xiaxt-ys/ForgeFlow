package com.forgeflow.worker.rbac;

import com.forgeflow.worker.tool.ToolCapability;

/**
 * The caller's role does not allow this tool capability.
 */
public class ToolAccessDeniedException extends RuntimeException {

    public ToolAccessDeniedException(String tool, ToolCapability capability, Role role) {
        super("role " + role + " may not call " + capability + " tool " + tool);
    }
}
