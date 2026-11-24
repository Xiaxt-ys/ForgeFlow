package com.forgeflow.worker.multiagent;

/**
 * Entry-agent strategy: turn a user prompt into a {@link TaskPlan}. Implementations may be
 * deterministic (rules) or model-driven; the scheduler and aggregator do not care.
 */
public interface TaskPlanStrategy {

    TaskPlan plan(String prompt);

    /**
     * Plans with a session memory block. Implementations decide how much of it to carry into
     * subtask prompts; sub-agents themselves never read session memory.
     */
    default TaskPlan plan(String prompt, String sessionContext) {
        return plan(prompt);
    }
}
