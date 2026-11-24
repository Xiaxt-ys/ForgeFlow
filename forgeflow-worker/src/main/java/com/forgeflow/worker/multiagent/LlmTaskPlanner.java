package com.forgeflow.worker.multiagent;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.prompt.SimplePrompt;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Model-driven entry agent. It asks the model for a JSON plan and validates it strictly;
 * anything unexpected falls back to the deterministic {@link TaskPlanner}, so a bad model
 * response degrades quality instead of failing the task.
 *
 * <p>Low-confidence routes are not guessed: they become a single generic turn, same
 * contract as the rule planner.</p>
 */
public final class LlmTaskPlanner implements TaskPlanStrategy {

    private static final Logger log = LoggerFactory.getLogger(LlmTaskPlanner.class);

    private final Supplier<ChatModel> modelSupplier;
    private final TaskPlanStrategy fallback;
    private final int maxSubtasks;
    private final double clarifyThreshold;

    public LlmTaskPlanner(Supplier<ChatModel> modelSupplier, TaskPlanStrategy fallback,
                          int maxSubtasks, double clarifyThreshold) {
        this.modelSupplier = modelSupplier;
        this.fallback = fallback;
        this.maxSubtasks = Math.max(2, maxSubtasks);
        this.clarifyThreshold = clarifyThreshold;
    }

    @Override
    public TaskPlan plan(String prompt) {
        return plan(prompt, "");
    }

    @Override
    public TaskPlan plan(String prompt, String sessionContext) {
        if (prompt == null || prompt.isBlank()) {
            return fallback.plan(prompt, sessionContext);
        }
        try {
            ChatModel model = modelSupplier.get();
            if (model == null) {
                return fallback.plan(prompt, sessionContext);
            }
            ChatOptions options = new ChatOptions();
            options.setTemperature(0f);
            options.setThinkingEnabled(false);
            String raw = model.chat(instruction(prompt, sessionContext), options);
            TaskPlan plan = parse(raw, prompt, sessionContext);
            if (plan == null) {
                log.warn("llm planner returned an unusable plan, falling back to rules");
                return fallback.plan(prompt, sessionContext);
            }
            log.info("llm plan mode={} route={} confidence={} subtasks={}",
                    plan.mode(), plan.route(), plan.confidence(), plan.subtasks().size());
            return plan;
        } catch (RuntimeException failure) {
            log.warn("llm planner failed, falling back to rules: {}", failure.getMessage());
            return fallback.plan(prompt, sessionContext);
        }
    }

    private TaskPlan parse(String raw, String prompt, String sessionContext) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = stripFences(raw);
        JSONObject object = JSON.parseObject(json);
        if (object == null) {
            return null;
        }
        String route = object.getString("route");
        route = route == null || route.isBlank() ? "general" : route.trim().toLowerCase();
        double confidence = object.getDoubleValue("confidence");
        if (confidence < clarifyThreshold) {
            // Not enough signal: do not guess a route, run a single generic turn.
            return singleWithContext(prompt, "general", confidence, sessionContext);
        }
        String mode = object.getString("mode");
        if (!"COMPLEX".equalsIgnoreCase(mode)) {
            return singleWithContext(prompt, route, confidence, sessionContext);
        }
        JSONArray array = object.getJSONArray("subtasks");
        if (array == null || array.size() < 2) {
            return singleWithContext(prompt, route, confidence, sessionContext);
        }
        int count = Math.min(array.size(), maxSubtasks);
        List<PlannedSubtask> subtasks = new ArrayList<>(count);
        Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) {
                return null;
            }
            String id = item.getString("id");
            id = id == null || id.isBlank() ? "s" + (i + 1) : id.trim();
            String stepPrompt = item.getString("prompt");
            if (stepPrompt == null || stepPrompt.isBlank()) {
                return null;
            }
            List<String> dependsOn = new ArrayList<>();
            JSONArray deps = item.getJSONArray("dependsOn");
            if (deps != null) {
                for (Object dependency : deps) {
                    String value = String.valueOf(dependency);
                    if (!ids.contains(value)) {
                        // Only earlier subtasks may be dependencies.
                        return null;
                    }
                    dependsOn.add(value);
                }
            }
            ids.add(id);
            subtasks.add(new PlannedSubtask(id, stepPrompt.trim(), dependsOn));
        }
        return new TaskPlan(PlanMode.COMPLEX, route, confidence, subtasks, prompt);
    }

    private static String stripFences(String raw) {
        String trimmed = raw.trim();
        int start = trimmed.indexOf("```");
        if (start < 0) {
            return trimmed;
        }
        int bodyStart = trimmed.indexOf('\n', start);
        int end = trimmed.indexOf("```", start + 3);
        if (bodyStart > 0 && end > bodyStart) {
            return trimmed.substring(bodyStart + 1, end).trim();
        }
        return trimmed;
    }

    private static TaskPlan singleWithContext(String prompt, String route, double confidence,
                                              String context) {
        String withContext = context == null || context.isBlank() ? prompt : context + prompt;
        return new TaskPlan(PlanMode.SIMPLE, route, confidence,
                List.of(new PlannedSubtask("s1", withContext, List.of())), prompt);
    }

    private static String instruction(String prompt, String context) {
        String memory = context == null || context.isBlank()
                ? ""
                : "\nSession memory (may be relevant; use it only if it helps):\n" + context;
        return """
                You are ForgeFlow's entry router. Decide whether the request needs one agent
                turn or several subtasks, then answer with JSON ONLY (no markdown):
                {"mode":"SIMPLE|COMPLEX","route":"research|code|writing|analysis|ops|general",
                 "confidence":0.0-1.0,
                 "subtasks":[{"id":"s1","prompt":"...","dependsOn":[]}]}

                Rules:
                - Use SIMPLE with a single subtask when one focused turn can answer.
                - Use COMPLEX only when the request clearly has multiple independent or
                  sequential steps; subtask prompts must be self-contained.
                - dependsOn may only reference earlier subtask ids.
                - If you cannot tell what the user wants, use SIMPLE, route "general",
                  and a confidence below 0.5 instead of guessing.
                """ + memory + "\nUser request:\n" + prompt;
    }
}
