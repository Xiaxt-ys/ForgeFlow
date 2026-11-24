package com.forgeflow.worker.multiagent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Output of the entry agent: where the request goes, how confident we are, and — for
 * complex requests — the subtasks plus their dependencies.
 */
public record TaskPlan(PlanMode mode, String route, double confidence,
                       List<PlannedSubtask> subtasks, String prompt) {

    public TaskPlan {
        subtasks = subtasks == null ? List.of() : List.copyOf(subtasks);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < subtasks.size(); i++) {
            PlannedSubtask subtask = subtasks.get(i);
            if (!seen.add(subtask.id())) {
                throw new IllegalArgumentException("duplicate subtask id: " + subtask.id());
            }
            for (String dependency : subtask.dependsOn()) {
                if (!seen.contains(dependency)) {
                    // Dependencies must point backwards so the plan is acyclic by construction.
                    throw new IllegalArgumentException(
                            "subtask " + subtask.id() + " depends on unknown or later subtask " + dependency);
                }
            }
        }
    }

    public static TaskPlan single(String prompt, String route, double confidence) {
        return new TaskPlan(PlanMode.SIMPLE, route, confidence,
                List.of(new PlannedSubtask("s1", prompt, List.of())), prompt);
    }

    public boolean isComplex() {
        return mode == PlanMode.COMPLEX && subtasks.size() > 1;
    }
}
