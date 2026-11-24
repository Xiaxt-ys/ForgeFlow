package com.forgeflow.worker.loop;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoopGuardTest {

    private static final LoopGuard GUARD = new LoopGuard(true, 20, 3, 4);

    private static List<Message> prompt(String text) {
        return List.of(new UserMessage(text));
    }

    @Test
    void sameToolAndArgsRepeatedHitsTheRepeatSignal() {
        LoopGuard guard = new LoopGuard(true, 20, 3, 4);
        guard.reset("t1");
        guard.onToolCall("t1", "search", Map.of("q", "redis lease"));
        guard.onToolCall("t1", "search", Map.of("q", "redis lease"));

        assertThatThrownBy(() -> guard.onToolCall("t1", "search", Map.of("q", "redis lease")))
                .isInstanceOf(LoopDetectedException.class)
                .hasMessageContaining("repeat")
                .hasMessageContaining("search");
    }

    @Test
    void differentArgumentsAreNotALoop() {
        LoopGuard guard = new LoopGuard(true, 20, 3, 4);
        guard.reset("t1");

        assertThatCode(() -> {
            for (int i = 0; i < 5; i++) {
                guard.onToolCall("t1", "search", Map.of("q", "query-" + i));
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void stepCapStopsALongTurn() {
        LoopGuard guard = new LoopGuard(true, 2, 10, 10);
        guard.reset("t1");
        guard.onToolCall("t1", "search", Map.of("q", "a"));
        guard.onToolCall("t1", "read", Map.of("path", "b"));

        assertThatThrownBy(() -> guard.onToolCall("t1", "search", Map.of("q", "c")))
                .isInstanceOf(LoopDetectedException.class)
                .hasMessageContaining("step-cap");
    }

    @Test
    void identicalPromptRepeatedHitsTheStableStateSignal() {
        LoopGuard guard = new LoopGuard(true, 20, 10, 3);
        guard.reset("t1");
        guard.onPrompt("t1", prompt("same"));
        guard.onPrompt("t1", prompt("same"));

        assertThatThrownBy(() -> guard.onPrompt("t1", prompt("same")))
                .isInstanceOf(LoopDetectedException.class)
                .hasMessageContaining("stable-state");
    }

    @Test
    void changedPromptResetsTheStableStateCounter() {
        LoopGuard guard = new LoopGuard(true, 20, 10, 3);
        guard.reset("t1");
        guard.onPrompt("t1", prompt("a"));
        guard.onPrompt("t1", prompt("a"));
        guard.onPrompt("t1", prompt("b"));

        assertThatCode(() -> {
            guard.onPrompt("t1", prompt("b"));
        }).doesNotThrowAnyException();
    }

    @Test
    void resetClearsCountersBetweenTasks() {
        LoopGuard guard = new LoopGuard(true, 20, 2, 4);
        guard.reset("t1");
        guard.onToolCall("t1", "search", Map.of("q", "a"));
        guard.reset("t1");

        assertThatCode(() -> guard.onToolCall("t1", "search", Map.of("q", "a")))
                .doesNotThrowAnyException();
    }

    @Test
    void disabledGuardNeverThrows() {
        LoopGuard guard = new LoopGuard(false, 1, 2, 2);
        guard.reset("t1");

        assertThatCode(() -> {
            for (int i = 0; i < 10; i++) {
                guard.onToolCall("t1", "search", Map.of("q", "a"));
                guard.onPrompt("t1", prompt("same"));
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void untrackedTaskIsIgnored() {
        assertThatCode(() -> GUARD.onToolCall(null, "search", Map.of("q", "a")))
                .doesNotThrowAnyException();
    }
}
