package com.forgeflow.worker.multiagent;

/**
 * Routing decision of the entry agent. SIMPLE goes straight to a single ReAct turn;
 * COMPLEX goes through the planner and the dependency-aware scheduler.
 */
public enum PlanMode {
    SIMPLE,
    COMPLEX
}
