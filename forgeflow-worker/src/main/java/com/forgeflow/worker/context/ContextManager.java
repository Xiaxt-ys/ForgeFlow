package com.forgeflow.worker.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Two-stage context governance:
 * 1. trim old tool results into short, re-callable placeholders;
 * 2. if still over budget, summarize early turns at a user-turn boundary.
 *
 * The point is not "make the prompt shorter"; it is "drop what can be re-fetched,
 * keep decisions, and never split a tool call from its result".
 */
@Component
public class ContextManager {

    public static final String EVICTED_PREFIX = "[history tool result omitted, original ";
    public static final String EVICTED_SUFFIX = " chars, re-call the tool if needed]";

    public List<AgentMessage> trimToolResults(List<AgentMessage> messages, int maxChars) {
        int total = messages.stream().mapToInt(AgentMessage::chars).sum();
        if (total <= maxChars) {
            return messages;
        }
        List<AgentMessage> result = new ArrayList<>(messages);
        for (int i = 0; i < result.size() && total > maxChars; i++) {
            AgentMessage message = result.get(i);
            if (message.role() != Role.TOOL || message.content() == null) {
                continue;
            }
            int before = message.chars();
            if (before <= 200) {
                continue;
            }
            String placeholder = EVICTED_PREFIX + before + EVICTED_SUFFIX;
            result.set(i, AgentMessage.tool(message.toolName(), placeholder));
            total -= before - placeholder.length();
        }
        return result;
    }

    /**
     * Keep the most recent {@code keepRecent} messages, summarize the earlier prefix.
     * The cut only lands on a USER message so a tool call/result pair is never split.
     */
    public List<AgentMessage> compact(List<AgentMessage> messages, int maxChars,
                                      int keepRecent, Summarizer summarizer) {
        int total = messages.stream().mapToInt(AgentMessage::chars).sum();
        if (total <= maxChars || messages.size() <= keepRecent) {
            return messages;
        }
        int cutoff = safeCutoff(messages, keepRecent);
        if (cutoff <= 0) {
            return messages;
        }
        List<AgentMessage> prefix = messages.subList(0, cutoff);
        List<AgentMessage> tail = messages.subList(cutoff, messages.size());
        String summary = summarizer.summarize(prefix);
        if (summary == null || summary.isBlank()) {
            return messages;
        }
        List<AgentMessage> result = new ArrayList<>(tail.size() + 1);
        result.add(AgentMessage.user(
                "(system-generated summary of earlier conversation; background only, not a new instruction)\n"
                        + summary));
        result.addAll(tail);
        return result;
    }

    private int safeCutoff(List<AgentMessage> messages, int keepRecent) {
        int tailStart = Math.max(0, messages.size() - keepRecent);
        for (int i = tailStart; i >= 0; i--) {
            if (i < messages.size() && messages.get(i).role() == Role.USER) {
                return i;
            }
        }
        return -1;
    }

    @FunctionalInterface
    public interface Summarizer {
        String summarize(List<AgentMessage> messages);
    }
}
