package com.forgeflow.store;

/**
 * A tool call parked for human approval: which turn and call it belongs to, plus the exact
 * canonical arguments hash the token will be bound to.
 */
public record ApprovalRecord(String id, String taskId, String turnId, String callId,
                             String tool, String argsHash, String argsJson, String status) {
}
