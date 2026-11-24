package com.forgeflow.worker.memory;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.Prompt;
import com.forgeflow.store.SessionMemoryStore;
import com.forgeflow.store.SessionRepository;
import com.forgeflow.worker.model.RoutingChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionMemoryServiceTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RoutingChatModel> noRouting() {
        ObjectProvider<RoutingChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ChatModel> modelProvider(ChatModel model) {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(model);
        return provider;
    }

    @Test
    void ruleLearnerAppendsTheTurnToTheSummary() {
        SessionRepository repository = mock(SessionRepository.class);
        when(repository.sessionForTask("task-1")).thenReturn(Optional.of("s1"));
        when(repository.summary("s1")).thenReturn(Optional.empty());
        SessionMemoryService service = new SessionMemoryService(
                new SessionMemoryStore(repository), noRouting(), modelProvider(null),
                "rule", 2000, 30, 2000);

        service.remember("task-1", "My name is Alice", "Nice to meet you, Alice.");

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(repository).saveSummary(eq("s1"), summary.capture(), any());
        assertThat(summary.getValue()).contains("My name is Alice").contains("Alice");
    }

    @Test
    void llmLearnerPersistsSummaryAndFacts() {
        SessionRepository repository = mock(SessionRepository.class);
        when(repository.sessionForTask("task-1")).thenReturn(Optional.of("s1"));
        when(repository.summary("s1")).thenReturn(Optional.empty());
        ChatModel model = stubModel("""
                {"summary":"Alice works on ForgeFlow","state":"none",
                 "facts":[{"key":"name","value":"Alice"},{"key":"project","value":"ForgeFlow"}]}
                """);
        SessionMemoryService service = new SessionMemoryService(
                new SessionMemoryStore(repository), noRouting(), modelProvider(model),
                "llm", 2000, 30, 2000);

        service.remember("task-1", "I am Alice, I work on ForgeFlow", "Noted.");

        verify(repository).saveSummary(eq("s1"), eq("Alice works on ForgeFlow"), eq("none"));
        verify(repository).upsertFact(eq("s1"), eq("name"), eq("Alice"), eq("task-1"),
                eq(false), any());
        verify(repository).upsertFact(eq("s1"), eq("project"), eq("ForgeFlow"), eq("task-1"),
                eq(false), any());
    }

    @Test
    void llmLearnerFallsBackToRulesOnBadJson() {
        SessionRepository repository = mock(SessionRepository.class);
        when(repository.sessionForTask("task-1")).thenReturn(Optional.of("s1"));
        when(repository.summary("s1")).thenReturn(Optional.empty());
        SessionMemoryService service = new SessionMemoryService(
                new SessionMemoryStore(repository), noRouting(), modelProvider(stubModel("not json")),
                "llm", 2000, 30, 2000);

        service.remember("task-1", "hello", "hi");

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(repository).saveSummary(eq("s1"), summary.capture(), any());
        assertThat(summary.getValue()).contains("hello");
    }

    @Test
    void hallucinatedFactsAreDropped() {
        SessionRepository repository = mock(SessionRepository.class);
        when(repository.sessionForTask("task-1")).thenReturn(Optional.of("s1"));
        when(repository.summary("s1")).thenReturn(Optional.empty());
        ChatModel model = stubModel("""
                {"summary":"s","state":"",
                 "facts":[{"key":"name","value":"Alice"},
                          {"key":"password","value":"hunter2"},
                          {"key":"city","value":"Paris"}]}
                """);
        SessionMemoryService service = new SessionMemoryService(
                new SessionMemoryStore(repository), noRouting(), modelProvider(model),
                "llm", 2000, 30, 2000);

        service.remember("task-1", "I am Alice, I work on ForgeFlow", "Noted.");

        verify(repository).upsertFact(eq("s1"), eq("name"), eq("Alice"), eq("task-1"),
                eq(false), any());
        verify(repository, never()).upsertFact(eq("s1"), eq("password"), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(repository, never()).upsertFact(eq("s1"), eq("city"), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void paraphrasedFactWithTokenOverlapSurvives() {
        SessionRepository repository = mock(SessionRepository.class);
        when(repository.sessionForTask("task-1")).thenReturn(Optional.of("s1"));
        when(repository.summary("s1")).thenReturn(Optional.empty());
        ChatModel model = stubModel("""
                {"summary":"s","state":"",
                 "facts":[{"key":"project","value":"Alice builds ForgeFlow project"}]}
                """);
        SessionMemoryService service = new SessionMemoryService(
                new SessionMemoryStore(repository), noRouting(), modelProvider(model),
                "llm", 2000, 30, 2000);

        service.remember("task-1", "I am Alice and ForgeFlow is my project", "ok");

        verify(repository).upsertFact(eq("s1"), eq("project"), eq("Alice builds ForgeFlow project"),
                eq("task-1"), eq(false), any());
    }

    @Test
    void factsRestatedFromThePreviousSummaryAreAccepted() {
        SessionRepository repository = mock(SessionRepository.class);
        when(repository.sessionForTask("task-1")).thenReturn(Optional.of("s1"));
        // The learner may restate a durable fact that came from an earlier turn.
        when(repository.tenantOf("s1")).thenReturn(Optional.of("tenant-a"));
        when(repository.summary("s1")).thenReturn(Optional.of(new com.forgeflow.store.SessionSummary(
                "s1", "user is Alice", null, 1, java.time.Instant.now())));
        ChatModel model = stubModel("""
                {"summary":"s","state":"","facts":[{"key":"name","value":"Alice"}]}
                """);
        SessionMemoryService service = new SessionMemoryService(
                new SessionMemoryStore(repository), noRouting(), modelProvider(model),
                "llm", 2000, 30, 2000);

        service.remember("task-1", "hi", "ok");

        verify(repository).upsertFact(eq("s1"), eq("name"), eq("Alice"), eq("task-1"),
                eq(false), any());
    }

    @Test
    void duplicateKeysInOneBatchAreCollapsed() {
        SessionRepository repository = mock(SessionRepository.class);
        when(repository.sessionForTask("task-1")).thenReturn(Optional.of("s1"));
        when(repository.summary("s1")).thenReturn(Optional.empty());
        ChatModel model = stubModel("""
                {"summary":"s","state":"",
                 "facts":[{"key":"name","value":"Alice"},{"key":"name","value":"Alice"}]}
                """);
        SessionMemoryService service = new SessionMemoryService(
                new SessionMemoryStore(repository), noRouting(), modelProvider(model),
                "llm", 2000, 30, 2000);

        service.remember("task-1", "I am Alice", "ok");

        verify(repository).upsertFact(eq("s1"), eq("name"), eq("Alice"), eq("task-1"),
                eq(false), any());
    }

    private static ChatModel stubModel(String reply) {
        return new ChatModel() {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                return new AiMessageResponse(null, reply, new AiMessage(reply));
            }

            @Override
            public void chatStream(Prompt prompt, StreamResponseListener listener,
                                   ChatOptions options) {
            }
        };
    }
}
