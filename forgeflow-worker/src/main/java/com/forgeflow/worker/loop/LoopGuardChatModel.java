package com.forgeflow.worker.loop;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.Prompt;
import com.forgeflow.worker.tool.TaskContextHolder;

/**
 * Delegating ChatModel that hashes each prompt before sending it. If the same prompt is
 * sent several times in a row, the agent is not making progress (signal 2).
 */
public final class LoopGuardChatModel implements ChatModel {

    private final ChatModel delegate;
    private final LoopGuard guard;

    public LoopGuardChatModel(ChatModel delegate, LoopGuard guard) {
        this.delegate = delegate;
        this.guard = guard;
    }

    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        guard.onPrompt(TaskContextHolder.get(), prompt.getMessages());
        return delegate.chat(prompt, options);
    }

    @Override
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        guard.onPrompt(TaskContextHolder.get(), prompt.getMessages());
        delegate.chatStream(prompt, listener, options);
    }
}
