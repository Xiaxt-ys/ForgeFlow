package com.forgeflow.worker.loop;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.SimplePrompt;
import com.forgeflow.worker.tool.TaskContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoopGuardChatModelTest {

    private final ChatModel delegate = new ChatModel() {
        @Override
        public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
            return null;
        }

        @Override
        public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        }
    };

    @AfterEach
    void clear() {
        TaskContextHolder.clear();
    }

    @Test
    void streamingPromptsAreCheckedAgainstTheStableStateSignal() {
        LoopGuard guard = new LoopGuard(true, 20, 10, 2);
        guard.reset("t1");
        TaskContextHolder.set("t1", "tenant-a");
        LoopGuardChatModel model = new LoopGuardChatModel(delegate, guard);

        model.chatStream(new SimplePrompt("same prompt"), (context, response) -> {
        }, new ChatOptions());

        assertThatThrownBy(() -> model.chatStream(new SimplePrompt("same prompt"),
                (context, response) -> {
                }, new ChatOptions()))
                .isInstanceOf(LoopDetectedException.class)
                .hasMessageContaining("stable-state");
    }
}
