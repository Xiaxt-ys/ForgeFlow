package com.forgeflow.worker.memory;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.forgeflow.store.SessionMemoryStore;
import com.forgeflow.store.SessionSnapshot;
import com.forgeflow.worker.model.RoutingChatModel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Session memory lifecycle:
 *
 * <ul>
 *   <li>entry: build a compact context block (rolling summary + newest facts) for the planner;</li>
 *   <li>exit: after a successful task, learn an updated summary and durable facts and persist
 *       them asynchronously, with the source task id as provenance.</li>
 * </ul>
 *
 * <p>Learning can be rule-based (append, no extra model call) or model-based (JSON summary +
 * facts). Model failures fall back to the rule path, so memory never blocks a task.</p>
 */
@Component
@ConditionalOnProperty(name = "forgeflow.memory.enabled", havingValue = "true",
        matchIfMissing = true)
public class SessionMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryService.class);

    /** Share of a fact's tokens that must appear in the source material. */
    private static final double MIN_SUPPORT = 0.7;

    private final SessionMemoryStore store;
    private final ObjectProvider<RoutingChatModel> routingModel;
    private final ObjectProvider<ChatModel> chatModel;
    private final boolean llmLearner;
    private final int maxContextChars;
    private final int maxFacts;
    private final int maxSummaryChars;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ff-memory-writer");
        thread.setDaemon(true);
        return thread;
    });

    public SessionMemoryService(SessionMemoryStore store,
                                ObjectProvider<RoutingChatModel> routingModel,
                                ObjectProvider<ChatModel> chatModel,
                                @Value("${forgeflow.memory.learner:rule}") String learner,
                                @Value("${forgeflow.memory.max-context-chars:2000}") int maxContextChars,
                                @Value("${forgeflow.memory.max-facts:30}") int maxFacts,
                                @Value("${forgeflow.memory.max-summary-chars:2000}") int maxSummaryChars) {
        this.store = store;
        this.routingModel = routingModel;
        this.chatModel = chatModel;
        this.llmLearner = "llm".equalsIgnoreCase(learner);
        this.maxContextChars = Math.max(200, maxContextChars);
        this.maxFacts = Math.max(1, maxFacts);
        this.maxSummaryChars = Math.max(200, maxSummaryChars);
    }

    /** Context block for the entry agent; empty when the task has no session. */
    public String contextFor(String taskId) {
        return contextFor(taskId, null);
    }

    /**
     * Context block tailored to this prompt: facts are selected by similarity (hybrid recall)
     * instead of always taking the newest ones, and the ones actually injected are reinforced.
     */
    public String contextFor(String taskId, String prompt) {
        return store.sessionForTask(taskId)
                .map(sessionId -> store.buildContext(sessionId, prompt, maxFacts, maxContextChars))
                .orElse("");
    }

    /** Fire-and-forget: memory write failures are logged, never surfaced to the task. */
    public void rememberAsync(String taskId, String prompt, String answer) {
        if (answer == null || answer.isBlank()) {
            return;
        }
        writer.submit(() -> {
            try {
                remember(taskId, prompt, answer);
            } catch (RuntimeException failure) {
                log.warn("session memory write failed task={}: {}", taskId, failure.getMessage());
            }
        });
    }

    void remember(String taskId, String prompt, String answer) {
        Optional<String> sessionId = store.sessionForTask(taskId);
        if (sessionId.isEmpty()) {
            return;
        }
        String id = sessionId.get();
        String prior = store.snapshot(id).map(SessionSnapshot::summary).orElse("");
        Learned learned = llmLearner
                ? learnWithModel(id, prior, prompt, answer)
                : ruleLearn(prior, prompt, answer);
        List<Fact> facts = validateFacts(learned.facts(),
                (prior == null ? "" : prior) + "\n" + prompt + "\n" + answer);
        int rejected = learned.facts().size() - facts.size();
        store.writeSummary(id, learned.summary(), learned.state());
        facts.forEach(fact -> store.writeFact(id, fact.key(), fact.value(), taskId));
        log.info("session.memory task={} session={} summaryChars={} facts={} rejected={}",
                taskId, id, learned.summary().length(), facts.size(), rejected);
    }

    /**
     * Causal check: a fact is only persisted when its value is traceable to the material the
     * learner was given (previous summary + this turn). Whole-value containment is tried first;
     * otherwise at least {@link #MIN_SUPPORT} of the value's tokens must appear. This is what
     * stops a model from inventing a preference, a name or a decision that was never said.
     */
    List<Fact> validateFacts(List<Fact> facts, String source) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        String haystack = normalize(source);
        List<Fact> kept = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Fact fact : facts) {
            boolean duplicateKey = !seen.add(fact.key());
            boolean usableKey = fact.key().length() <= 64 && !fact.key().isBlank();
            if (duplicateKey || !usableKey || !supported(fact.value(), haystack)) {
                log.debug("session.memory.rejected key={} value={} duplicate={} usableKey={}",
                        fact.key(), fact.value(), duplicateKey, usableKey);
                continue;
            }
            kept.add(fact);
        }
        return kept;
    }

    private static boolean supported(String value, String haystack) {
        String whole = normalize(value);
        if (whole.isEmpty()) {
            return false;
        }
        if (haystack.contains(whole)) {
            return true;
        }
        String[] tokens = value.toLowerCase(java.util.Locale.ROOT).split("\\s+");
        int total = 0;
        int hits = 0;
        for (String token : tokens) {
            String normalized = normalize(token);
            if (normalized.isEmpty()) {
                continue;
            }
            total++;
            if (haystack.contains(normalized)) {
                hits++;
            }
        }
        return total > 0 && (double) hits / total >= MIN_SUPPORT;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private Learned ruleLearn(String prior, String prompt, String answer) {
        String addition = "Q: " + oneLine(prompt) + "\nA: " + truncate(oneLine(answer), 400);
        String merged = prior == null || prior.isBlank() ? addition : prior + "\n" + addition;
        if (merged.length() > maxSummaryChars) {
            merged = "…" + merged.substring(merged.length() - maxSummaryChars + 1);
        }
        return new Learned(merged, null, List.of());
    }

    private Learned learnWithModel(String sessionId, String prior, String prompt, String answer) {
        try {
            ChatModel model = routingModel.getIfAvailable();
            if (model == null) {
                model = chatModel.getIfUnique();
            }
            if (model == null) {
                return ruleLearn(prior, prompt, answer);
            }
            ChatOptions options = new ChatOptions();
            options.setTemperature(0f);
            options.setThinkingEnabled(false);
            String raw = model.chat(learnerPrompt(prior, prompt, answer), options);
            Learned learned = parseLearned(raw);
            return learned != null ? learned : ruleLearn(prior, prompt, answer);
        } catch (RuntimeException failure) {
            log.warn("llm memory learner failed session={}: {}", sessionId, failure.getMessage());
            return ruleLearn(prior, prompt, answer);
        }
    }

    private Learned parseLearned(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = stripFences(raw);
        JSONObject object = JSON.parseObject(json);
        if (object == null) {
            return null;
        }
        String summary = object.getString("summary");
        if (summary == null || summary.isBlank()) {
            return null;
        }
        if (summary.length() > maxSummaryChars) {
            summary = summary.substring(summary.length() - maxSummaryChars);
        }
        String state = object.getString("state");
        List<Fact> facts = new ArrayList<>();
        JSONArray array = object.getJSONArray("facts");
        if (array != null) {
            for (int i = 0; i < array.size() && facts.size() < maxFacts; i++) {
                JSONObject item = array.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String key = item.getString("key");
                String value = item.getString("value");
                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    facts.add(new Fact(key.trim(), value.trim()));
                }
            }
        }
        return new Learned(summary, state, facts);
    }

    private static String learnerPrompt(String prior, String prompt, String answer) {
        return """
                You maintain the long-term memory of one conversation session.
                Given the previous summary and the new turn, return JSON ONLY:
                {"summary":"...","state":"...","facts":[{"key":"...","value":"..."}]}

                Rules:
                - summary: rolling summary of the whole session, at most 1500 characters;
                - state: decisions, constraints, open questions (may be empty);
                - facts: only durable facts worth remembering (names, preferences, chosen
                  technologies, decisions). Skip transient details and secrets.
                - Every fact value must be stated in the conversation; if it is not, do not
                  emit it. Never invent facts that are not in the conversation.

                Previous summary:
                """ + (prior == null ? "" : prior) + """
                New turn:
                User: """ + prompt + "\nAssistant: " + answer;
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

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').trim();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    @PreDestroy
    void shutdown() {
        writer.shutdownNow();
    }

    private record Fact(String key, String value) {
    }

    private record Learned(String summary, String state, List<Fact> facts) {
    }
}
