package com.forgeflow.worker.multiagent;

import java.util.List;

/**
 * One node of the plan. {@code dependsOn} holds ids of subtasks that must succeed before
 * this one may start; an empty list means it can run in parallel with its siblings.
 */
public record PlannedSubtask(String id, String prompt, List<String> dependsOn) {

    public PlannedSubtask {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
