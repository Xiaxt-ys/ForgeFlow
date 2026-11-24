package com.forgeflow.worker.multiagent;

/**
 * Lets a sub-agent register its turn with the executor so streamed model deltas can be
 * attributed to the right task and subtask.
 */
public interface TurnTracker {

    void track(String turnId, String taskId, String source);

    void release(String turnId);
}
