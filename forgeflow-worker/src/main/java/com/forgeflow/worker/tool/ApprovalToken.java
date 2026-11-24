package com.forgeflow.worker.tool;

/**
 * An approval is not a boolean. It is a capability bound to one task, one step, one tool,
 * one argument hash and an expiry. Changing any of them invalidates the token.
 */
public record ApprovalToken(String value) {
}
