package com.forgeflow.worker.multiagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Entry agent: decides route, complexity and subtask dependencies.
 *
 * <p>The decision is rule-based on purpose — it is deterministic, testable and costs no
 * tokens. When the route confidence is below the threshold the planner does not guess:
 * it falls back to a single generic turn and keeps the low confidence in the plan so a
 * caller can ask the user to clarify.</p>
 *
 * <p>Swapping in a model-based planner means replacing this class only; the scheduler and
 * aggregator consume {@link TaskPlan} and do not care where it came from.</p>
 */
public final class TaskPlanner implements TaskPlanStrategy {

    private static final Map<String, List<String>> ROUTE_KEYWORDS = new LinkedHashMap<>();

    static {
        ROUTE_KEYWORDS.put("research", List.of("查", "搜索", "检索", "调研", "资料", "新闻", "文档"));
        ROUTE_KEYWORDS.put("code", List.of("代码", "实现", "重构", "修复", "测试", "函数", "接口", "bug", "编译"));
        ROUTE_KEYWORDS.put("writing", List.of("写", "撰写", "总结", "报告", "文案", "翻译", "大纲", "润色"));
        ROUTE_KEYWORDS.put("analysis", List.of("分析", "对比", "评估", "计算", "优缺点", "论证", "复盘"));
        ROUTE_KEYWORDS.put("ops", List.of("部署", "上线", "排查", "日志", "监控", "告警", "回滚"));
    }

    /** These connectors imply that the next step needs the previous results. */
    private static final List<String> SEQUENTIAL_CONNECTORS =
            List.of("然后", "接着", "之后", "基于", "最后", "接下来", "再把", "再用");
    /** These connectors imply sibling steps that can run in parallel. */
    private static final List<String> PARALLEL_CONNECTORS =
            List.of("并且", "同时", "另外", "以及", "分别", "顺便");

    private final int simpleMaxChars;
    private final int maxSubtasks;
    private final double clarifyThreshold;

    public TaskPlanner(int simpleMaxChars, int maxSubtasks, double clarifyThreshold) {
        this.simpleMaxChars = Math.max(1, simpleMaxChars);
        this.maxSubtasks = Math.max(1, maxSubtasks);
        this.clarifyThreshold = clarifyThreshold;
    }

    @Override
    public TaskPlan plan(String prompt) {
        return plan(prompt, "");
    }

    @Override
    public TaskPlan plan(String prompt, String sessionContext) {
        TaskPlan base = rulePlan(prompt);
        if (sessionContext == null || sessionContext.isBlank()) {
            return base;
        }
        // Rule mode has no model to curate facts, so each subtask gets the compact block.
        List<PlannedSubtask> withContext = new ArrayList<>(base.subtasks().size());
        for (PlannedSubtask subtask : base.subtasks()) {
            withContext.add(new PlannedSubtask(subtask.id(),
                    sessionContext + subtask.prompt(), subtask.dependsOn()));
        }
        return new TaskPlan(base.mode(), base.route(), base.confidence(), withContext, base.prompt());
    }

    private TaskPlan rulePlan(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        Route route = detectRoute(text);
        if (route.confidence() < clarifyThreshold) {
            // Not enough signal to route: fall back to a plain turn instead of guessing.
            return new TaskPlan(PlanMode.SIMPLE, "general", route.confidence(),
                    List.of(new PlannedSubtask("s1", text, List.of())), text);
        }

        List<String> steps = splitSteps(text);
        // Short prompts only become a plan when the user wrote explicit separators;
        // otherwise "写代码然后测试" is one request, not two subtasks.
        if (steps.size() < 2 || (text.length() <= simpleMaxChars && !hasExplicitSeparator(text))) {
            return new TaskPlan(PlanMode.SIMPLE, route.label(), route.confidence(),
                    List.of(new PlannedSubtask("s1", text, List.of())), text);
        }

        if (steps.size() > maxSubtasks) {
            steps = new ArrayList<>(steps.subList(0, maxSubtasks));
        }
        List<PlannedSubtask> subtasks = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            String step = steps.get(i);
            List<String> dependsOn = List.of();
            if (i > 0 && startsWith(step, SEQUENTIAL_CONNECTORS)) {
                // A sequential step may consume any earlier result, so wait for all of them.
                List<String> previous = new ArrayList<>(i);
                for (int j = 0; j < i; j++) {
                    previous.add("s" + (j + 1));
                }
                dependsOn = previous;
            } else if (i > 0 && startsWith(step, PARALLEL_CONNECTORS)) {
                dependsOn = List.of();
            }
            subtasks.add(new PlannedSubtask("s" + (i + 1), step, dependsOn));
        }
        return new TaskPlan(PlanMode.COMPLEX, route.label(), route.confidence(), subtasks, text);
    }

    private Route detectRoute(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String best = "general";
        int bestHits = 0;
        for (Map.Entry<String, List<String>> entry : ROUTE_KEYWORDS.entrySet()) {
            int hits = 0;
            for (String keyword : entry.getValue()) {
                int index = lower.indexOf(keyword);
                while (index >= 0) {
                    hits++;
                    index = lower.indexOf(keyword, index + keyword.length());
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = entry.getKey();
            }
        }
        double confidence = bestHits == 0 ? 0.3 : Math.min(0.9, 0.45 + 0.15 * bestHits);
        return new Route(best, confidence);
    }

    private List<String> splitSteps(String text) {
        List<String> parts = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            for (String piece : line.split("[;；]")) {
                addIfUseful(parts, piece);
            }
        }
        if (parts.size() >= 2) {
            return normalize(parts);
        }

        List<String> byConnector = new ArrayList<>();
        for (String piece : text.split("(?=然后|接着|之后|最后|接下来|另外|同时|并且|以及)")) {
            addIfUseful(byConnector, piece);
        }
        if (byConnector.size() >= 2) {
            return normalize(byConnector);
        }

        List<String> byNumber = new ArrayList<>();
        for (String piece : text.split("(?=(?:^|\\s)\\d+[.、)])")) {
            addIfUseful(byNumber, piece);
        }
        if (byNumber.size() >= 2) {
            return normalize(byNumber);
        }
        return normalize(parts.isEmpty() ? List.of(text) : parts);
    }

    /** Drops list headings and numbering prefixes so each step is a self-contained prompt. */
    private static List<String> normalize(List<String> parts) {
        List<String> cleaned = new ArrayList<>(parts.size());
        for (String part : parts) {
            String step = part.replaceFirst("^\\d+[.、)]\\s*", "").trim();
            if (step.length() < 4) {
                continue;
            }
            if (parts.size() > 1 && (step.endsWith("：") || step.endsWith(":"))) {
                // A heading like "请完成一个代码任务：" is not a step of its own.
                continue;
            }
            cleaned.add(step);
        }
        return cleaned.isEmpty() ? List.of() : cleaned;
    }

    private static void addIfUseful(List<String> parts, String piece) {
        String trimmed = piece == null ? "" : piece.trim();
        if (trimmed.length() >= 4) {
            parts.add(trimmed);
        }
    }

    private static boolean startsWith(String text, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExplicitSeparator(String text) {
        return text.contains("\n") || text.contains("；") || text.contains(";")
                || text.matches("(?s).*(^|\\s)\\d+[.、)].*");
    }

    private record Route(String label, double confidence) {
    }
}
