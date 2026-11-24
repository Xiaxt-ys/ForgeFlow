package com.forgeflow.worker.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeflow.worker.context.AgentMessage;
import com.forgeflow.worker.context.ContextManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Synthetic 30-task x 3-run comparison. Token count is estimated as chars/4.
 * "Completion" checks that every task's KEY facts survive context assembly.
 */
public final class ContextBenchmark {

    private final ContextAssembler assembler;

    public ContextBenchmark(ContextAssembler assembler) {
        this.assembler = assembler;
    }

    public ContextBenchmarkReport run(int tasks, int runs, int maxChars) {
        long baselineChars = 0;
        long compressedChars = 0;
        int baselineComplete = 0;
        int compressedComplete = 0;
        int total = tasks * runs;

        for (int task = 0; task < tasks; task++) {
            List<AgentMessage> baseline = contextFor(task);
            String key1 = "KEY:" + task + "-a";
            String key2 = "KEY:" + task + "-b";
            for (int run = 0; run < runs; run++) {
                baselineChars += baseline.stream().mapToInt(AgentMessage::chars).sum();
                if (containsAll(baseline, key1, key2)) {
                    baselineComplete++;
                }
                List<AgentMessage> compressed = assembler.assemble(
                        "bench-" + task, "topic-" + task, baseline, maxChars);
                compressedChars += compressed.stream().mapToInt(AgentMessage::chars).sum();
                if (containsAll(compressed, key1, key2)) {
                    compressedComplete++;
                }
            }
        }

        double reduction = baselineChars == 0 ? 0
                : 1.0 - (double) compressedChars / baselineChars;
        return new ContextBenchmarkReport(tasks, runs, baselineChars / 4, compressedChars / 4,
                reduction, (double) baselineComplete / total, (double) compressedComplete / total);
    }

    private List<AgentMessage> contextFor(int task) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system("KEY:" + task + "-a\nKEY:" + task + "-b"));
        for (int i = 0; i < 6; i++) {
            messages.add(AgentMessage.tool("search", "old-tool-result-" + task + "-" + i
                    + "-" + "x".repeat(800)));
        }
        messages.add(AgentMessage.user("question-" + task));
        messages.add(AgentMessage.assistant("answer-" + task));
        return messages;
    }

    private boolean containsAll(List<AgentMessage> messages, String... keys) {
        String joined = messages.stream().map(AgentMessage::content).reduce("", (a, b) -> a + "\n" + b);
        for (String key : keys) {
            if (!joined.contains(key)) {
                return false;
            }
        }
        return true;
    }

    public static ContextAssembler assembler(Path workspace) {
        ObjectMapper mapper = new ObjectMapper();
        LongTermMemory memory = new LongTermMemory(workspace);
        memory.appendFact("KEY:0-a");
        TopicIndex topics = new TopicIndex(workspace, mapper);
        topics.put("topic-0", "docs/topic-0.md");
        return new ContextAssembler(memory, topics, new ContextManager(),
                new ExecutionTrace(workspace, mapper), workspace);
    }
}
