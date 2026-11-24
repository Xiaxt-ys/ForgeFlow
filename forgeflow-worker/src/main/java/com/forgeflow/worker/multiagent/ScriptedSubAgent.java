package com.forgeflow.worker.multiagent;

import com.forgeflow.worker.context.AgentMessage;

import java.util.Map;

/**
 * Deterministic sub-agent used for the concurrency benchmark and tests.
 * It exercises reasoning events, read tools and occasional write tools.
 */
public final class ScriptedSubAgent implements SubAgent {

    private final long sleepMs;

    public ScriptedSubAgent(long sleepMs) {
        this.sleepMs = sleepMs;
    }

    @Override
    public SubtaskResult execute(Subtask subtask, AgentContext context, ToolRouter router,
                                 AgentEventBus events) {
        context.append(AgentMessage.user(subtask.prompt()));
        events.emit(new ReasoningDelta(subtask.id(), "plan-" + subtask.index()));

        ToolResult read = router.invoke("ws-" + subtask.id(), subtask, "search",
                Map.of("q", subtask.prompt()),
                () -> ToolResult.ok("result-" + subtask.id()));
        events.emit(new ToolCallEvent(subtask.id(), "search", true));
        context.append(AgentMessage.tool("search", read.output()));

        if (subtask.index() % 5 == 0) {
            ToolResult write = router.invoke("ws-" + subtask.id(), subtask, "write_note",
                    Map.of("note", "n-" + subtask.id()),
                    () -> ToolResult.ok("written-" + subtask.id()));
            events.emit(new ToolCallEvent(subtask.id(), "write_note", false));
            context.append(AgentMessage.tool("write_note", write.output()));
        }

        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new SubtaskResult(subtask.id(), "interrupted", false);
            }
        }
        context.append(AgentMessage.assistant("done-" + subtask.id()));
        return new SubtaskResult(subtask.id(), "done-" + subtask.id(), true);
    }
}
