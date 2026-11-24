package com.forgeflow.worker;

import com.forgeflow.worker.context.AgentMessage;
import com.forgeflow.worker.context.ContextManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextManagerTest {

    private final ContextManager manager = new ContextManager();

    @Test
    void trimReplacesOldToolResultsWithRecallablePlaceholder() {
        String longResult = "x".repeat(1_000);
        List<AgentMessage> messages = List.of(
                AgentMessage.user("question"),
                AgentMessage.tool("search", longResult),
                AgentMessage.assistant("answer"),
                AgentMessage.user("follow-up"),
                AgentMessage.tool("search", longResult),
                AgentMessage.assistant("answer-2"));

        List<AgentMessage> trimmed = manager.trimToolResults(messages, 600);

        assertThat(trimmed.get(1).content()).startsWith(ContextManager.EVICTED_PREFIX);
        assertThat(trimmed.get(4).content()).startsWith(ContextManager.EVICTED_PREFIX);
        assertThat(trimmed.get(0).content()).isEqualTo("question");
    }

    @Test
    void compactKeepsRecentTailAndPrefixesSummary() {
        List<AgentMessage> messages = List.of(
                AgentMessage.user("old-q"),
                AgentMessage.assistant("old-a"),
                AgentMessage.user("mid-q"),
                AgentMessage.assistant("mid-a"),
                AgentMessage.user("new-q"),
                AgentMessage.assistant("new-a"));

        List<AgentMessage> compacted = manager.compact(messages, 10, 2,
                prefix -> "summary of " + prefix.size() + " messages");

        assertThat(compacted).hasSizeLessThan(messages.size());
        assertThat(compacted.get(0).content()).contains("system-generated summary");
        assertThat(compacted.get(0).content()).contains("summary of 4 messages");
        assertThat(compacted.get(compacted.size() - 1).content()).isEqualTo("new-a");
    }

    @Test
    void compactDoesNothingWhenUnderBudget() {
        List<AgentMessage> messages = List.of(AgentMessage.user("q"), AgentMessage.assistant("a"));
        assertThat(manager.compact(messages, 10_000, 2, prefix -> "x")).isSameAs(messages);
    }
}
