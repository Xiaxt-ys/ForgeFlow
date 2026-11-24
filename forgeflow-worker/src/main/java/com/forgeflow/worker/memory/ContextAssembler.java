package com.forgeflow.worker.memory;

import com.forgeflow.worker.context.AgentMessage;
import com.forgeflow.worker.context.ContextManager;
import com.forgeflow.worker.context.Role;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the prompt context from three separated layers:
 * long-term facts, topic locators, and recent execution messages.
 */
public final class ContextAssembler {

    private final LongTermMemory memory;
    private final TopicIndex topics;
    private final ContextManager contextManager;
    private final ExecutionTrace trace;
    private final Path workspace;

    public ContextAssembler(LongTermMemory memory, TopicIndex topics,
                            ContextManager contextManager, ExecutionTrace trace, Path workspace) {
        this.memory = memory;
        this.topics = topics;
        this.contextManager = contextManager;
        this.trace = trace;
        this.workspace = workspace;
    }

    public List<AgentMessage> assemble(String taskId, String topic, List<AgentMessage> recent,
                                       int maxChars) {
        int before = recent.stream().mapToInt(AgentMessage::chars).sum();
        List<AgentMessage> all = new ArrayList<>();
        String facts = memory.facts().stream().map(f -> "- " + f).collect(Collectors.joining("\n"));
        String locator = topics.get(topic);
        all.add(AgentMessage.system("Long-term facts:\n" + facts
                + (locator == null ? "" : "\nTopic index: " + topic + " -> " + locator)));
        all.addAll(recent);

        List<AgentMessage> trimmed = contextManager.trimToolResults(all, maxChars);
        List<AgentMessage> compacted = contextManager.compact(trimmed, maxChars, 6, this::summarize);
        int after = compacted.stream().mapToInt(AgentMessage::chars).sum();
        trace.append(taskId, "context.assembled", Map.of(
                "topic", topic, "charsBefore", before, "charsAfter", after,
                "messages", compacted.size()));
        return compacted;
    }

    public String externalize(String taskId, String toolName, String content, int threshold) {
        if (content == null || content.length() <= threshold) {
            return content;
        }
        String name = "tool-output/" + taskId + "-" + UUID.randomUUID() + ".txt";
        try {
            Path file = workspace.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot externalize tool output", e);
        }
        trace.append(taskId, "tool.externalized", Map.of(
                "tool", toolName, "path", name, "chars", content.length()));
        return "[externalized: " + name + ", original " + content.length() + " chars]";
    }

    private String summarize(List<AgentMessage> messages) {
        return messages.stream()
                .filter(message -> message.role() == Role.SYSTEM
                        || (message.content() != null && message.content().contains("KEY:")))
                .map(AgentMessage::content)
                .collect(Collectors.joining("\n"));
    }
}
