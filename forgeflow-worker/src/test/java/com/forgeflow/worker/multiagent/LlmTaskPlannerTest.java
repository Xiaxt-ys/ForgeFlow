package com.forgeflow.worker.multiagent;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.Prompt;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmTaskPlannerTest {

    private final TaskPlanner rules = new TaskPlanner(200, 8, 0.5);

    private TaskPlan plan(String reply) {
        LlmTaskPlanner planner = new LlmTaskPlanner(() -> model(reply), rules, 8, 0.5);
        return planner.plan("先查资料；然后写报告");
    }

    private static ChatModel model(String reply) {
        return new ChatModel() {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                return new AiMessageResponse(null, reply, new AiMessage(reply));
            }

            @Override
            public void chatStream(Prompt prompt, StreamResponseListener listener,
                                   ChatOptions options) {
            }
        };
    }

    @Test
    void sessionContextReachesThePlannerAndSinglePrompt() {
        java.util.concurrent.atomic.AtomicReference<String> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        ChatModel capturing = new ChatModel() {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                String text = prompt.getMessages().isEmpty() ? ""
                        : prompt.getMessages().get(0).getTextContent();
                seen.set(text);
                String reply = "{\"mode\":\"SIMPLE\",\"route\":\"general\",\"confidence\":0.8,"
                        + "\"subtasks\":[{\"id\":\"s1\",\"prompt\":\"answer\"}]}";
                return new AiMessageResponse(null, reply, new AiMessage(reply));
            }

            @Override
            public void chatStream(Prompt prompt, StreamResponseListener listener,
                                   ChatOptions options) {
            }
        };
        LlmTaskPlanner planner = new LlmTaskPlanner(() -> capturing, rules, 8, 0.5);

        TaskPlan plan = planner.plan("what is my name",
                "[session memory]\nFacts:\n- name: Alice\n[/session memory]\n\n");

        assertThat(seen.get()).contains("Alice").contains("what is my name");
        assertThat(plan.subtasks().get(0).prompt()).contains("Alice").contains("what is my name");
    }

    @Test
    void modelPlanBecomesComplexWithDependency() {
        TaskPlan plan = plan("""
                {"mode":"COMPLEX","route":"research","confidence":0.9,
                 "subtasks":[
                   {"id":"s1","prompt":"search docs","dependsOn":[]},
                   {"id":"s2","prompt":"write report","dependsOn":["s1"]}]}
                """);

        assertThat(plan.mode()).isEqualTo(PlanMode.COMPLEX);
        assertThat(plan.route()).isEqualTo("research");
        assertThat(plan.subtasks()).hasSize(2);
        assertThat(plan.subtasks().get(1).dependsOn()).containsExactly("s1");
    }

    @Test
    void fencedJsonIsAccepted() {
        TaskPlan plan = plan("""
                ```json
                {"mode":"COMPLEX","route":"code","confidence":0.8,
                 "subtasks":[{"id":"s1","prompt":"a","dependsOn":[]},
                             {"id":"s2","prompt":"b","dependsOn":[]}]}
                ```
                """);

        assertThat(plan.mode()).isEqualTo(PlanMode.COMPLEX);
        assertThat(plan.subtasks()).hasSize(2);
    }

    @Test
    void simplePlanStaysSingle() {
        TaskPlan plan = plan("""
                {"mode":"SIMPLE","route":"writing","confidence":0.8,
                 "subtasks":[{"id":"s1","prompt":"answer directly","dependsOn":[]}]}
                """);

        assertThat(plan.mode()).isEqualTo(PlanMode.SIMPLE);
        assertThat(plan.subtasks()).hasSize(1);
    }

    @Test
    void lowConfidenceDoesNotGuessARoute() {
        TaskPlan plan = plan("""
                {"mode":"COMPLEX","route":"code","confidence":0.2,
                 "subtasks":[{"id":"s1","prompt":"a"},{"id":"s2","prompt":"b"}]}
                """);

        assertThat(plan.mode()).isEqualTo(PlanMode.SIMPLE);
        assertThat(plan.route()).isEqualTo("general");
    }

    @Test
    void malformedReplyFallsBackToRules() {
        TaskPlan plan = plan("not json at all");

        assertThat(plan.mode()).isEqualTo(PlanMode.COMPLEX);
        assertThat(plan.subtasks()).hasSize(2);
        assertThat(plan.subtasks().get(1).dependsOn()).containsExactly("s1");
    }
}
