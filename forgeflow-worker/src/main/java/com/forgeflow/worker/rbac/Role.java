package com.forgeflow.worker.rbac;

/**
 * Coarse-grained role used to gate tool capabilities.
 */
public enum Role {
    /** Read-only tools. */
    VIEWER,
    /** Read and write tools, but not dangerous ones. */
    OPERATOR,
    /** Everything, including dangerous tools (still subject to approval). */
    ADMIN;

    public static Role parse(String value, Role fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
