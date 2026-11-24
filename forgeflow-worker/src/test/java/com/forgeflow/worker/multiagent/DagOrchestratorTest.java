package com.forgeflow.worker.multiagent;

import com.forgeflow.common.ExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

class DagOrchestratorTest {

    private final AgentEventBus events = new AgentEventBus();
    private final DagOrchestrator orchestrator = new DagOrchestrator();

    @AfterEach
    void closeEvents() {
        events.close();
    }

    private static TaskPlan plan(PlannedSubtask... subtasks) {
        return new TaskPlan(PlanMode.COMPLEX, "code", 0.8, List.of(subtasks), "prompt");
    }

    @Test
    void mixedPlanGivesUpstreamOnlyToDependentSubtasks() throws InterruptedException {
        java.util.Map<String, String> prompts = new java.util.concurrent.ConcurrentHashMap<>();
        SubAgent agent = (subtask, context, router, bus) -> {
            prompts.put(subtask.id(), subtask.prompt());
            return new SubtaskResult(subtask.id(), "out-of-" + subtask.id(), true);
        };
        TaskPlan plan = plan(
                new PlannedSubtask("s1", "root", List.of()),
                new PlannedSubtask("s2", "depends", List.of("s1")),
                new PlannedSubtask("s3", "sibling", List.of()));

        OrchestrationResult result = orchestrator.run(
                plan, agent, null, events, ExecutionContext.never(), 3, 5_000);

        assertThat(result.succeeded()).isEqualTo(3);
        assertThat(prompts.get("s2"))
                .contains("[upstream results]")
                .contains("out-of-s1");
        assertThat(prompts.get("s3")).doesNotContain("[upstream results]");
    }

    @Test
    void dependentSubtaskReceivesUpstreamOutput() throws InterruptedException {
        List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        SubAgent agent = (subtask, context, router, bus) -> {
            prompts.add(subtask.id() + "|" + subtask.prompt());
            return new SubtaskResult(subtask.id(), "result-of-" + subtask.id(), true);
        };
        TaskPlan plan = plan(
                new PlannedSubtask("s1", "first", List.of()),
                new PlannedSubtask("s2", "second", List.of("s1")));

        orchestrator.run(plan, agent, null, events, ExecutionContext.never(), 4, 5_000);

        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1))
                .contains("second")
                .contains("[upstream results]")
                .contains("result-of-s1");
    }

    @Test
    void dependentSubtaskWaitsForItsDependency() throws InterruptedException {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        SubAgent agent = (subtask, context, router, bus) -> {
            order.add(subtask.id());
            LockSupport.parkNanos(20_000_000L);
            return new SubtaskResult(subtask.id(), "ok", true);
        };
        TaskPlan plan = plan(
                new PlannedSubtask("s1", "first", List.of()),
                new PlannedSubtask("s2", "second", List.of("s1")));

        OrchestrationResult result = orchestrator.run(
                plan, agent, null, events, ExecutionContext.never(), 4, 5_000);

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(order).containsExactly("s1", "s2");
    }

    @Test
    void independentSubtasksRunInParallel() throws InterruptedException {
        SubAgent agent = (subtask, context, router, bus) -> {
            LockSupport.parkNanos(400_000_000L);
            return new SubtaskResult(subtask.id(), "ok", true);
        };
        TaskPlan plan = plan(
                new PlannedSubtask("s1", "a", List.of()),
                new PlannedSubtask("s2", "b", List.of()));

        OrchestrationResult result = orchestrator.run(
                plan, agent, null, events, ExecutionContext.never(), 2, 5_000);

        assertThat(result.succeeded()).isEqualTo(2);
        // Sequential execution would take ~800ms.
        assertThat(result.elapsedMs()).isLessThan(700);
    }

    @Test
    void failedDependencyCascadesToSkipped() throws InterruptedException {
        List<String> executed = Collections.synchronizedList(new ArrayList<>());
        SubAgent agent = (subtask, context, router, bus) -> {
            executed.add(subtask.id());
            return new SubtaskResult(subtask.id(), "boom", false);
        };
        TaskPlan plan = plan(
                new PlannedSubtask("s1", "first", List.of()),
                new PlannedSubtask("s2", "second", List.of("s1")));

        OrchestrationResult result = orchestrator.run(
                plan, agent, null, events, ExecutionContext.never(), 4, 5_000);

        assertThat(executed).containsExactly("s1");
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.allSucceeded()).isFalse();
    }

    @Test
    void cancellationSkipsEverythingNotStarted() throws InterruptedException {
        SubAgent agent = (subtask, context, router, bus) ->
                new SubtaskResult(subtask.id(), "ok", true);

        OrchestrationResult result = orchestrator.run(
                plan(new PlannedSubtask("s1", "a", List.of())),
                agent, null, events, () -> true, 2, 5_000);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.succeeded()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void timeoutIsReportedAndDoesNotHang() throws InterruptedException {
        SubAgent agent = (subtask, context, router, bus) -> {
            LockSupport.parkNanos(2_000_000_000L);
            return new SubtaskResult(subtask.id(), "late", true);
        };

        OrchestrationResult result = orchestrator.run(
                plan(new PlannedSubtask("s1", "a", List.of())),
                agent, null, events, ExecutionContext.never(), 1, 150);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.succeeded()).isZero();
    }
}
