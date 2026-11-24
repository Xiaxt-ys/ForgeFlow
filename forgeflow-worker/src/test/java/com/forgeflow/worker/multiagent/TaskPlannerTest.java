package com.forgeflow.worker.multiagent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPlannerTest {

    private final TaskPlanner planner = new TaskPlanner(200, 8, 0.5);

    @Test
    void shortSingleRequestStaysSimple() {
        TaskPlan plan = planner.plan("帮我总结一下这段话的要点");

        assertThat(plan.mode()).isEqualTo(PlanMode.SIMPLE);
        assertThat(plan.subtasks()).hasSize(1);
        assertThat(plan.route()).isEqualTo("writing");
        assertThat(plan.confidence()).isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void numberedStepsBecomeComplexPlan() {
        TaskPlan plan = planner.plan("""
                请完成一个代码任务：
                1. 阅读现有实现并找出 bug
                2. 修复 bug 并补充测试
                3. 写一份变更总结
                """);

        assertThat(plan.mode()).isEqualTo(PlanMode.COMPLEX);
        assertThat(plan.isComplex()).isTrue();
        assertThat(plan.subtasks()).hasSize(3);
        assertThat(plan.route()).isEqualTo("code");
    }

    @Test
    void sequentialConnectorCreatesDependency() {
        TaskPlan plan = planner.plan("先查一下资料并整理；然后基于资料写一份报告");

        assertThat(plan.isComplex()).isTrue();
        assertThat(plan.subtasks()).hasSize(2);
        assertThat(plan.subtasks().get(0).dependsOn()).isEmpty();
        assertThat(plan.subtasks().get(1).dependsOn()).containsExactly("s1");
    }

    @Test
    void sessionContextIsPrependedToEachSubtask() {
        TaskPlan plan = planner.plan("先查资料；然后写报告",
                "[session memory]\nFacts:\n- name: Alice\n[/session memory]\n\n");

        assertThat(plan.subtasks()).hasSize(2);
        assertThat(plan.subtasks()).allSatisfy(subtask ->
                assertThat(subtask.prompt()).contains("Alice"));
    }

    @Test
    void threeSequentialStepsFormAChain() {
        TaskPlan plan = planner.plan("先查资料；然后写报告；最后校对一遍");

        assertThat(plan.isComplex()).isTrue();
        assertThat(plan.subtasks()).hasSize(3);
        assertThat(plan.subtasks().get(1).dependsOn()).containsExactly("s1");
        assertThat(plan.subtasks().get(2).dependsOn()).containsExactly("s1", "s2");
    }

    @Test
    void siblingStepsHaveNoDependency() {
        TaskPlan plan = planner.plan("查一下北京天气；查一下上海天气");

        assertThat(plan.isComplex()).isTrue();
        assertThat(plan.subtasks()).hasSize(2);
        assertThat(plan.subtasks()).allSatisfy(subtask -> assertThat(subtask.dependsOn()).isEmpty());
    }

    @Test
    void lowConfidenceFallsBackInsteadOfGuessing() {
        TaskPlan plan = planner.plan("嗯……这个东西你看着办吧，随便弄一下就行");

        assertThat(plan.route()).isEqualTo("general");
        assertThat(plan.confidence()).isLessThan(0.5);
        assertThat(plan.mode()).isEqualTo(PlanMode.SIMPLE);
    }

    @Test
    void subtasksAreCapped() {
        TaskPlanner small = new TaskPlanner(200, 2, 0.5);
        TaskPlan plan = small.plan("1. 分析代码\n2. 修复 bug\n3. 写测试\n4. 写总结");

        assertThat(plan.subtasks()).hasSize(2);
    }

    @Test
    void planRejectsForwardDependency() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new TaskPlan(PlanMode.COMPLEX, "code", 0.8, List.of(
                        new PlannedSubtask("s1", "a", List.of("s2")),
                        new PlannedSubtask("s2", "b", List.of())), "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("s1");
    }
}
