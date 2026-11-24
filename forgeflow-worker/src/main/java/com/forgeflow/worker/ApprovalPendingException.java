package com.forgeflow.worker;

/**
 * The agent turn suspended because a tool call needs human approval. The executor has
 * already persisted the pending call; the worker parks the task in WAITING_APPROVAL.
 */
public class ApprovalPendingException extends RuntimeException {

    private final String tool;
    private final String turnId;
    private final String callId;

    public ApprovalPendingException(String tool, String turnId, String callId) {
        super("tool '" + tool + "' requires approval (turn=" + turnId + ", call=" + callId + ")");
        this.tool = tool;
        this.turnId = turnId;
        this.callId = callId;
    }

    public String tool() {
        return tool;
    }

    public String turnId() {
        return turnId;
    }

    public String callId() {
        return callId;
    }
}
