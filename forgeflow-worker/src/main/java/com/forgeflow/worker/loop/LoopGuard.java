package com.forgeflow.worker.loop;

import com.agentsflex.core.message.Message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Three independent dead-loop signals, all scoped to one task:
 *
 * <ol>
 *   <li><b>repeat</b> — the same tool with the same arguments is called too many times;</li>
 *   <li><b>stable state</b> — the prompt sent to the model is byte-identical several times in
 *       a row, meaning no tool result or new reasoning entered the context;</li>
 *   <li><b>step cap</b> — the turn executed more tool calls than the configured maximum.</li>
 * </ol>
 *
 * <p>Any signal raises {@link LoopDetectedException}. Signal 2 is checked on every model call
 * (via {@link LoopGuardChatModel}), signal 1 and 3 on every tool call (via
 * {@link LoopGuardToolInterceptor}).</p>
 */
public final class LoopGuard {

    private final boolean enabled;
    private final int maxSteps;
    private final int repeatThreshold;
    private final int stableStateThreshold;
    private final Map<String, TaskState> states = new ConcurrentHashMap<>();

    public LoopGuard(boolean enabled, int maxSteps, int repeatThreshold, int stableStateThreshold) {
        this.enabled = enabled;
        this.maxSteps = Math.max(1, maxSteps);
        this.repeatThreshold = Math.max(2, repeatThreshold);
        this.stableStateThreshold = Math.max(2, stableStateThreshold);
    }

    /** Starts fresh counters for a task; called once before a turn is executed. */
    public void reset(String taskId) {
        if (enabled && taskId != null) {
            states.put(taskId, new TaskState());
        }
    }

    public void onToolCall(String taskId, String toolName, Map<String, Object> args) {
        if (!enabled || taskId == null) {
            return;
        }
        TaskState state = stateFor(taskId);
        String key = toolName + "(" + canonical(args) + ")";
        synchronized (state) {
            state.toolCalls++;
            if (state.toolCalls > maxSteps) {
                throw new LoopDetectedException("step-cap",
                        taskId + " executed " + state.toolCalls + " tool calls, limit is " + maxSteps);
            }
            int repeats = state.calls.merge(key, 1, Integer::sum);
            if (repeats >= repeatThreshold) {
                throw new LoopDetectedException("repeat",
                        taskId + " called " + key + " " + repeats + " times");
            }
        }
    }

    public void onPrompt(String taskId, List<Message> messages) {
        if (!enabled || taskId == null) {
            return;
        }
        String hash = hash(messages);
        TaskState state = stateFor(taskId);
        synchronized (state) {
            if (hash.equals(state.lastHash)) {
                state.sameHash++;
                if (state.sameHash >= stableStateThreshold) {
                    throw new LoopDetectedException("stable-state",
                            taskId + " sent an identical prompt " + state.sameHash + " times");
                }
            } else {
                state.lastHash = hash;
                state.sameHash = 1;
            }
        }
    }

    private TaskState stateFor(String taskId) {
        return states.computeIfAbsent(taskId, id -> new TaskState());
    }

    private static String canonical(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(args.size());
        args.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parts.add(entry.getKey() + "=" + entry.getValue()));
        return String.join(",", parts);
    }

    private static String hash(List<Message> messages) {
        StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            text.append(message.getClass().getSimpleName()).append('\u0000')
                    .append(message.getTextContent()).append('\u0001');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static final class TaskState {
        private final Map<String, Integer> calls = new ConcurrentHashMap<>();
        private String lastHash;
        private int sameHash;
        private int toolCalls;
    }
}
