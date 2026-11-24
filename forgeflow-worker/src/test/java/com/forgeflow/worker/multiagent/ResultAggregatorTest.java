package com.forgeflow.worker.multiagent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultAggregatorTest {

    private final ResultAggregator aggregator = new ResultAggregator();

    @Test
    void mergesSubtaskOutputsInPlanOrder() {
        TaskPlan plan = new TaskPlan(PlanMode.COMPLEX, "code", 0.8, List.of(
                new PlannedSubtask("s1", "阅读代码", List.of()),
                new PlannedSubtask("s2", "修复 bug", List.of("s1"))), "prompt");
        OrchestrationResult result = new OrchestrationResult(List.of(
                new SubtaskResult("s1", "found the bug", true, 100, 20, 2),
                new SubtaskResult("s2", "fixed it", true, 150, 30, 3)),
                2, 0, 0, false, false, 42);

        ResultAggregator.Aggregate aggregate = aggregator.aggregate(plan, result);

        assertThat(aggregate.output()).contains("found the bug").contains("fixed it");
        assertThat(aggregate.output().indexOf("found the bug"))
                .isLessThan(aggregate.output().indexOf("fixed it"));
        assertThat(aggregate.metadata())
                .containsEntry("mode", "COMPLEX")
                .containsEntry("route", "code")
                .containsEntry("succeeded", 2)
                .containsEntry("promptTokens", 250L)
                .containsEntry("completionTokens", 50L)
                .containsEntry("steps", 5);
    }

    @Test
    void singleSubtaskIsReturnedAsIs() {
        TaskPlan plan = TaskPlan.single("hello", "general", 0.9);
        OrchestrationResult result = new OrchestrationResult(
                List.of(new SubtaskResult("s1", "world", true)),
                1, 0, 0, false, false, 5);

        assertThat(aggregator.aggregate(plan, result).output()).isEqualTo("world");
    }

    @Test
    void llmSynthesizerReplacesConcatenation() {
        TaskPlan plan = new TaskPlan(PlanMode.COMPLEX, "code", 0.8, List.of(
                new PlannedSubtask("s1", "a", List.of()),
                new PlannedSubtask("s2", "b", List.of())), "prompt");
        OrchestrationResult result = new OrchestrationResult(List.of(
                new SubtaskResult("s1", "part one", true),
                new SubtaskResult("s2", "part two", true)),
                2, 0, 0, false, false, 5);

        ResultAggregator.Aggregate aggregate =
                aggregator.aggregate(plan, result, prompt -> "merged answer");

        assertThat(aggregate.output()).isEqualTo("merged answer");
        assertThat(aggregate.metadata()).containsEntry("aggregator", "llm");
    }

    @Test
    void failingSynthesizerFallsBackToConcatenation() {
        TaskPlan plan = new TaskPlan(PlanMode.COMPLEX, "code", 0.8, List.of(
                new PlannedSubtask("s1", "a", List.of()),
                new PlannedSubtask("s2", "b", List.of())), "prompt");
        OrchestrationResult result = new OrchestrationResult(List.of(
                new SubtaskResult("s1", "part one", true),
                new SubtaskResult("s2", "part two", true)),
                2, 0, 0, false, false, 5);

        ResultAggregator.Aggregate aggregate =
                aggregator.aggregate(plan, result, prompt -> {
                    throw new IllegalStateException("model down");
                });

        assertThat(aggregate.output()).contains("part one").contains("part two");
        assertThat(aggregate.metadata()).containsEntry("aggregator", "concat");
    }

    @Test
    void failedSubtaskIsMarkedInOutput() {
        TaskPlan plan = new TaskPlan(PlanMode.COMPLEX, "code", 0.8, List.of(
                new PlannedSubtask("s1", "a", List.of()),
                new PlannedSubtask("s2", "b", List.of())), "prompt");
        OrchestrationResult result = new OrchestrationResult(List.of(
                new SubtaskResult("s1", "ok", true),
                new SubtaskResult("s2", "skipped: dependency failed", false)),
                1, 0, 1, false, false, 10);

        ResultAggregator.Aggregate aggregate = aggregator.aggregate(plan, result);

        assertThat(aggregate.output()).contains("[failed]");
        assertThat(aggregate.metadata()).containsEntry("skipped", 1);
        List<?> subtasks = (List<?>) aggregate.metadata().get("subtasks");
        assertThat(((Map<?, ?>) subtasks.get(1)).get("dependsOn")).isEqualTo(List.of());
    }
}
