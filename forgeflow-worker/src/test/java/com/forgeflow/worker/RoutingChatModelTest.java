package com.forgeflow.worker;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.SimplePrompt;
import com.forgeflow.worker.model.ModelHealthStore;
import com.forgeflow.worker.model.RoutingChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingChatModelTest {

    @Test
    void fallsBackAndOpensBrokenCandidate() {
        ChatModel broken = model("broken", () -> {
            throw new IllegalStateException("boom");
        });
        ChatModel healthy = model("ok", () -> response("ok"));
        ModelHealthStore health = new ModelHealthStore(1, 60_000);
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Candidate("broken", broken),
                new RoutingChatModel.Candidate("healthy", healthy)),
                health, Duration.ofMillis(200));

        assertThat(routing.chat(new SimplePrompt("hi"), new ChatOptions()).getMessage().getContent())
                .isEqualTo("ok");
        assertThat(health.state("broken")).isEqualTo(ModelHealthStore.State.OPEN);
    }

    @Test
    void streamingUsesTheFirstHealthyCandidate() {
        java.util.concurrent.atomic.AtomicReference<String> used =
                new java.util.concurrent.atomic.AtomicReference<>();
        ChatModel first = streamingModel("first", used);
        ChatModel second = streamingModel("second", used);
        ModelHealthStore health = new ModelHealthStore(1, 60_000);
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Candidate("first", first),
                new RoutingChatModel.Candidate("second", second)),
                health, Duration.ofMillis(200));

        routing.chatStream(new SimplePrompt("hi"), (context, response) -> {
        }, new ChatOptions());

        assertThat(used.get()).isEqualTo("first");
    }

    private static ChatModel streamingModel(String id,
                                            java.util.concurrent.atomic.AtomicReference<String> used) {
        return new ChatModel() {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                throw new UnsupportedOperationException("streaming only");
            }

            @Override
            public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
                used.set(id);
            }
        };
    }

    @Test
    void firstPacketTimeoutFallsBack() {
        ChatModel slow = model("slow", () -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return response("slow");
        });
        ChatModel healthy = model("ok", () -> response("fallback"));
        ModelHealthStore health = new ModelHealthStore(1, 60_000);
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Candidate("slow", slow),
                new RoutingChatModel.Candidate("healthy", healthy)),
                health, Duration.ofMillis(50));

        assertThat(routing.chat(new SimplePrompt("hi"), new ChatOptions()).getMessage().getContent())
                .isEqualTo("fallback");
        assertThat(health.state("slow")).isEqualTo(ModelHealthStore.State.OPEN);
    }

    @Test
    void allCandidatesFailThrows() {
        ChatModel broken = model("broken", () -> {
            throw new IllegalStateException("boom");
        });
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Candidate("broken", broken)),
                new ModelHealthStore(1, 60_000), Duration.ofMillis(50));

        assertThatThrownBy(() -> routing.chat(new SimplePrompt("hi"), new ChatOptions()))
                .hasMessageContaining("all model candidates failed");
    }

    private ChatModel model(String id, ThrowingSupplier<AiMessageResponse> supplier) {
        return new ChatModel() {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                try {
                    return supplier.get();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void chatStream(Prompt prompt, StreamResponseListener listener,
                                   ChatOptions options) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private AiMessageResponse response(String content) {
        return new AiMessageResponse(null, content, new AiMessage(content));
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
