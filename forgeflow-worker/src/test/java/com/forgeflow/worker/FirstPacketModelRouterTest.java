package com.forgeflow.worker;

import com.forgeflow.common.NoAvailableModelException;
import com.forgeflow.worker.model.FirstPacketModelRouter;
import com.forgeflow.worker.model.ModelClient;
import com.forgeflow.worker.model.ModelHealthStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirstPacketModelRouterTest {

    @Test
    void fallsBackToNextCandidateAfterFailure() {
        ModelClient broken = client("broken", CompletableFuture.failedFuture(new RuntimeException("boom")));
        ModelClient healthy = client("healthy", CompletableFuture.completedFuture("ok"));
        ModelHealthStore health = new ModelHealthStore(1, 60_000);
        FirstPacketModelRouter router = new FirstPacketModelRouter(List.of(broken, healthy), health,
                Duration.ofMillis(200));

        assertThat(router.complete("hi")).isEqualTo("ok");
        assertThat(health.state("broken")).isEqualTo(ModelHealthStore.State.OPEN);
        assertThat(health.state("healthy")).isEqualTo(ModelHealthStore.State.CLOSED);
    }

    @Test
    void firstPacketTimeoutIsTreatedAsFailure() {
        ModelClient slow = client("slow", new CompletableFuture<>());
        ModelClient healthy = client("healthy", CompletableFuture.completedFuture("fallback"));
        ModelHealthStore health = new ModelHealthStore(1, 60_000);
        FirstPacketModelRouter router = new FirstPacketModelRouter(List.of(slow, healthy), health,
                Duration.ofMillis(50));

        assertThat(router.complete("hi")).isEqualTo("fallback");
        assertThat(health.state("slow")).isEqualTo(ModelHealthStore.State.OPEN);
    }

    @Test
    void allCandidatesFailedThrows() {
        ModelClient broken = client("broken", CompletableFuture.failedFuture(new RuntimeException("boom")));
        FirstPacketModelRouter router = new FirstPacketModelRouter(List.of(broken),
                new ModelHealthStore(1, 60_000), Duration.ofMillis(50));

        assertThatThrownBy(() -> router.complete("hi"))
                .isInstanceOf(NoAvailableModelException.class);
    }

    private ModelClient client(String id, CompletableFuture<String> result) {
        return new ModelClient() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public CompletableFuture<String> complete(String prompt) {
                return result;
            }
        };
    }
}
