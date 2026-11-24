package com.forgeflow.worker.multiagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exit agent: turns ordered subtask results into the single task result, and records the
 * plan plus per-subtask status as structured metadata (the durable form of the
 * "structured intermediate result" contract between agents).
 *
 * <p>With a synthesizer supplied, the subtask outputs are merged by the model; any failure
 * falls back to deterministic concatenation, so the task never loses its result because the
 * summarizer failed.</p>
 */
public final class ResultAggregator {

    private static final Logger log = LoggerFactory.getLogger(ResultAggregator.class);

    public record Aggregate(String output, Map<String, Object> metadata) {
    }

    public Aggregate aggregate(TaskPlan plan, OrchestrationResult result) {
        return aggregate(plan, result, null);
    }

    /**
     * @param synthesizer optional model call that merges subtask outputs; null keeps the
     *                    deterministic concatenation used by local runs and tests
     */
    public Aggregate aggregate(TaskPlan plan, OrchestrationResult result,
                               Function<String, String> synthesizer) {
        Aggregate base = concatenate(plan, result);
        if (synthesizer == null || plan.subtasks().size() < 2) {
            return withAggregator(base, "concat");
        }
        try {
            String merged = synthesizer.apply(synthesisPrompt(plan, result));
            if (merged != null && !merged.isBlank()) {
                return withAggregator(new Aggregate(merged.trim(), base.metadata()), "llm");
            }
        } catch (RuntimeException failure) {
            log.warn("llm aggregation failed, using concatenation: {}", failure.getMessage());
        }
        return withAggregator(base, "concat");
    }

    private static Aggregate withAggregator(Aggregate base, String aggregator) {
        Map<String, Object> metadata = new LinkedHashMap<>(base.metadata());
        metadata.put("aggregator", aggregator);
        return new Aggregate(base.output(), metadata);
    }

    private static String synthesisPrompt(TaskPlan plan, OrchestrationResult result) {
        StringBuilder prompt = new StringBuilder("""
                You are ForgeFlow's exit agent. Merge the subtask results below into ONE
                final answer for the user. Preserve concrete facts, numbers and names.
                Do not mention subtask ids or internal steps unless it helps the reader.
                Answer in the same language as the subtask content. Do not invent content.

                User request:
                """);
        prompt.append(plan.prompt()).append("\n\nSubtask results:\n");
        for (int i = 0; i < plan.subtasks().size(); i++) {
            SubtaskResult subtaskResult = result.results().get(i);
            prompt.append("- ").append(plan.subtasks().get(i).id()).append(": ")
                    .append(subtaskResult.success() ? subtaskResult.output()
                            : "(failed: " + subtaskResult.output() + ")")
                    .append('\n');
        }
        return prompt.toString();
    }

    private Aggregate concatenate(TaskPlan plan, OrchestrationResult result) {
        StringBuilder output = new StringBuilder();
        if (plan.subtasks().size() == 1) {
            output.append(result.results().get(0).output());
        } else {
            for (int i = 0; i < plan.subtasks().size(); i++) {
                PlannedSubtask planned = plan.subtasks().get(i);
                SubtaskResult subtaskResult = result.results().get(i);
                output.append("## ").append(planned.id()).append(" · ").append(title(planned.prompt()))
                        .append('\n')
                        .append(subtaskResult.success() ? subtaskResult.output()
                                : "[failed] " + subtaskResult.output())
                        .append("\n\n");
            }
        }

        long promptTokens = 0;
        long completionTokens = 0;
        int steps = 0;
        List<Map<String, Object>> subtasks = new ArrayList<>(plan.subtasks().size());
        for (int i = 0; i < plan.subtasks().size(); i++) {
            PlannedSubtask planned = plan.subtasks().get(i);
            SubtaskResult subtaskResult = result.results().get(i);
            promptTokens += subtaskResult.promptTokens();
            completionTokens += subtaskResult.completionTokens();
            steps += subtaskResult.steps();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", planned.id());
            entry.put("dependsOn", planned.dependsOn());
            entry.put("success", subtaskResult.success());
            subtasks.add(entry);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", plan.mode().name());
        metadata.put("route", plan.route());
        metadata.put("confidence", plan.confidence());
        metadata.put("subtasks", subtasks);
        metadata.put("succeeded", result.succeeded());
        metadata.put("failed", result.failed());
        metadata.put("skipped", result.skipped());
        metadata.put("cancelled", result.cancelled());
        metadata.put("timedOut", result.timedOut());
        metadata.put("elapsedMs", result.elapsedMs());
        metadata.put("steps", steps);
        metadata.put("promptTokens", promptTokens);
        metadata.put("completionTokens", completionTokens);
        return new Aggregate(output.toString().trim(), metadata);
    }

    private static String title(String prompt) {
        String singleLine = prompt.replace('\n', ' ').trim();
        return singleLine.length() <= 30 ? singleLine : singleLine.substring(0, 30) + "…";
    }
}
