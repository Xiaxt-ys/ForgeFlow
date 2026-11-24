package com.forgeflow.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@Import({SessionRepository.class, SessionMemoryStore.class, MemoryEmbeddingConfig.class})
@Sql(scripts = "/schema.sql")
class SessionMemoryStoreTest {

    @Autowired
    SessionRepository repository;

    @Autowired
    SessionMemoryStore store;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ff_task_session");
        jdbc.update("DELETE FROM ff_session_fact_history");
        jdbc.update("DELETE FROM ff_session_fact");
        jdbc.update("DELETE FROM ff_session_summary");
        jdbc.update("DELETE FROM ff_session");
    }

    @Test
    void summaryAndFactsAreScopedToSession() {
        store.ensureSession("s1", "tenant-a");
        store.writeSummary("s1", "user is Alice, works on ForgeFlow", null);
        store.writeFact("s1", "name", "Alice", "task-1");
        store.writeFact("s1", "project", "ForgeFlow", "task-1");

        SessionSnapshot snapshot = store.snapshot("s1").orElseThrow();
        assertThat(snapshot.tenantId()).isEqualTo("tenant-a");
        assertThat(snapshot.summary()).contains("Alice");
        assertThat(snapshot.facts()).extracting(SessionFact::factKey)
                .contains("name", "project");

        String context = store.buildContext("s1", 10, 500);
        assertThat(context).contains("session memory").contains("Alice").contains("ForgeFlow");
    }

    @Test
    void sameFactKeyIsUpdatedNotDuplicated() {
        store.ensureSession("s1", "tenant-a");
        store.writeFact("s1", "city", "Beijing", "task-1");
        store.writeFact("s1", "city", "Shanghai", "task-2");

        assertThat(repository.facts("s1", 10)).hasSize(1);
        assertThat(repository.facts("s1", 10).get(0).factValue()).isEqualTo("Shanghai");
        assertThat(repository.facts("s1", 10).get(0).sourceTaskId()).isEqualTo("task-2");
        assertThat(repository.facts("s1", 10).get(0).version()).isEqualTo(2);
    }

    @Test
    void conflictIsRecordedInHistoryAndLatestWins() {
        store.ensureSession("s1", "tenant-a");
        store.writeFact("s1", "city", "Beijing", "task-1");
        store.writeFact("s1", "city", "Shanghai", "task-2");

        assertThat(store.history("s1", "city", 10))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.kind()).isEqualTo(FactHistory.CONFLICT);
                    assertThat(entry.oldValue()).isEqualTo("Beijing");
                    assertThat(entry.newValue()).isEqualTo("Shanghai");
                    assertThat(entry.sourceTaskId()).isEqualTo("task-2");
                });
        // A conflict raises importance so the fact survives the decay window while it matters.
        assertThat(repository.fact("s1", "city").orElseThrow().importance()).isGreaterThan(0.5);
    }

    @Test
    void refinementIsNotTreatedAsAConflict() {
        store.ensureSession("s1", "tenant-a");
        store.writeFact("s1", "name", "Alice", "task-1");
        store.writeFact("s1", "name", "Alice Chen", "task-2");

        assertThat(repository.fact("s1", "name").orElseThrow().factValue()).isEqualTo("Alice Chen");
        assertThat(store.history("s1", "name", 10))
                .singleElement()
                .extracting(FactHistory::kind)
                .isEqualTo(FactHistory.REFINED);
    }

    @Test
    void keepBothConflictPolicyMergesValues() {
        SessionMemoryStore keepBoth = new SessionMemoryStore(repository, "keep-both", "hybrid");
        keepBoth.ensureSession("s1", "tenant-a");
        keepBoth.writeFact("s1", "city", "Beijing", "task-1");
        keepBoth.writeFact("s1", "city", "Shanghai", "task-2");

        assertThat(repository.fact("s1", "city").orElseThrow().factValue())
                .isEqualTo("Beijing | Shanghai");
    }

    @Test
    void unusedLowImportanceFactsDecay() {
        store.ensureSession("s1", "tenant-a");
        store.writeFact("s1", "old-detail", "x", "task-1");

        int archived = repository.archiveStale(Instant.now().plusSeconds(1), 2, 0.8);

        assertThat(archived).isEqualTo(1);
        assertThat(repository.facts("s1", 10)).isEmpty();
        assertThat(store.snapshot("s1").orElseThrow().facts()).isEmpty();
    }

    @Test
    void recalledFactsAreReinforcedAndSurviveDecay() {
        store.ensureSession("s1", "tenant-a");
        store.writeFact("s1", "name", "Alice", "task-1");

        store.buildContext("s1", 10, 500);
        store.buildContext("s1", 10, 500);
        assertThat(repository.fact("s1", "name").orElseThrow().hits()).isEqualTo(2);
        assertThat(repository.fact("s1", "name").orElseThrow().lastUsedAt()).isNotNull();

        assertThat(repository.archiveStale(Instant.now().plusSeconds(1), 2, 0.8)).isZero();
        assertThat(repository.archiveStale(Instant.now().plusSeconds(1), 3, 0.8)).isEqualTo(1);
    }

    @Test
    void importantFactsNeverDecay() {
        store.ensureSession("s1", "tenant-a");
        store.writeFact("s1", "decision", "use Redis lease", "task-1");
        jdbc.update("UPDATE ff_session_fact SET importance = 0.95 WHERE session_id = 's1'");

        assertThat(repository.archiveStale(Instant.now().plusSeconds(1), 2, 0.8)).isZero();
    }

    @Test
    void overflowArchivesTheLowestRankedFacts() {
        store.ensureSession("s1", "tenant-a");
        for (int i = 1; i <= 5; i++) {
            store.writeFact("s1", "key-" + i, "value " + i, "task-" + i);
        }

        assertThat(repository.archiveOverflow("s1", 2)).isEqualTo(3);
        assertThat(repository.facts("s1", 10)).hasSize(2);
    }

    @Test
    void overflowKeepsFrequentlyRecalledFacts() {
        store.ensureSession("s1", "tenant-a");
        store.writeFact("s1", "key-1", "identity", "task-1");
        store.writeFact("s1", "key-2", "old detail", "task-2");
        store.writeFact("s1", "key-3", "newer detail", "task-3");
        repository.touch("s1", java.util.List.of("key-1"));
        repository.touch("s1", java.util.List.of("key-1"));

        assertThat(repository.archiveOverflow("s1", 1)).isEqualTo(2);
        assertThat(repository.facts("s1", 10))
                .extracting(SessionFact::factKey)
                .containsExactly("key-1");
    }

    @Test
    void vectorRecallPrefersTheFactThePromptAsksAbout() {
        SessionMemoryStore vector = new SessionMemoryStore(repository, "latest", "vector");
        vector.ensureSession("s1", "tenant-a");
        vector.writeFact("s1", "user_name", "Alice", "task-1");
        vector.writeFact("s1", "city", "Beijing", "task-2");
        vector.writeFact("s1", "pet", "dog", "task-3");
        vector.writeFact("s1", "car", "Volvo", "task-4");
        vector.writeFact("s1", "language", "Java", "task-5");
        vector.writeFact("s1", "food", "noodles", "task-6");

        String context = vector.buildContext("s1", "what is my name", 4, 800);

        assertThat(context).contains("Alice");
        assertThat(context).doesNotContain("noodles");
    }

    @Test
    void hybridRecallAlwaysKeepsTheNewestFacts() {
        store.ensureSession("s1", "tenant-a");
        String[][] facts = {
                {"user_name", "Alice"}, {"city", "Beijing"}, {"pet", "dog"},
                {"car", "Volvo"}, {"language", "Java"}, {"food", "noodles"}};
        Instant base = Instant.now().minusSeconds(600);
        for (int i = 0; i < facts.length; i++) {
            store.writeFact("s1", facts[i][0], facts[i][1], "task-" + (i + 1));
            // Pin distinct timestamps: ties would make "newest three" non-deterministic.
            jdbc.update("UPDATE ff_session_fact SET updated_at = ? WHERE session_id = 's1' "
                            + "AND fact_key = ?",
                    java.sql.Timestamp.from(base.plusSeconds(i)), facts[i][0]);
        }

        String context = store.buildContext("s1", "what is my name", 4, 800);

        assertThat(context).contains("Alice");
        // Anchors: the newest three are carried regardless of the query wording.
        assertThat(context).contains("noodles").contains("Java").contains("Volvo");
    }

    @Test
    void summaryWritesAreVersioned() {
        store.ensureSession("s1", "tenant-a");

        store.writeSummary("s1", "first", null);
        store.writeSummary("s1", "second", "state");

        SessionSummary summary = repository.summary("s1").orElseThrow();
        assertThat(summary.summary()).isEqualTo("second");
        assertThat(summary.structuredState()).isEqualTo("state");
        assertThat(summary.version()).isEqualTo(1);
    }

    @Test
    void switchingTheEmbeddingModelReEmbedsStoredFactsOnce() {
        CountingEmbedding first = new CountingEmbedding("fake-1");
        SessionMemoryStore before = new SessionMemoryStore(repository, first, "latest", "vector");
        before.ensureSession("s1", "tenant-a");
        before.writeFact("s1", "user_name", "Alice", "task-1");
        assertThat(repository.candidates("s1", 10).get(0).model()).isEqualTo("fake-1");

        CountingEmbedding second = new CountingEmbedding("fake-2");
        SessionMemoryStore after = new SessionMemoryStore(repository, second, "latest", "vector");
        String context = after.buildContext("s1", "what is my name", 5, 500);

        assertThat(context).contains("Alice");
        assertThat(repository.candidates("s1", 10).get(0).model()).isEqualTo("fake-2");
        int callsAfterRepair = second.calls.get();
        assertThat(callsAfterRepair).isGreaterThanOrEqualTo(1);

        // Second recall reuses the repaired vectors: one query embedding, no full re-embed.
        after.buildContext("s1", "what is my name", 5, 500);
        assertThat(second.calls.get()).isEqualTo(callsAfterRepair + 1);
    }

    /** Deterministic provider whose vector space is tagged by model id. */
    static final class CountingEmbedding implements EmbeddingProvider {
        private final String model;
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        CountingEmbedding(String model) {
            this.model = model;
        }

        @Override
        public String model() {
            return model;
        }

        @Override
        public Embedding embed(java.util.List<String> texts) {
            calls.incrementAndGet();
            return new Embedding(model,
                    texts.stream().map(TextVectorizer::vectorize).toList());
        }
    }

    @Test
    void vectorIndexDrivesRecallAndIsRepairedOnce() {
        FakeIndex index = new FakeIndex();
        SessionMemoryStore indexed = new SessionMemoryStore(repository,
                new HashEmbeddingProvider(), vectors(index), "latest", "vector");
        indexed.ensureSession("s1", "tenant-a");
        indexed.writeFact("s1", "user_name", "Alice", "task-1");
        indexed.writeFact("s1", "pet", "dog", "task-2");
        indexed.writeFact("s1", "food", "noodles", "task-3");

        assertThat(index.points).hasSize(3);
        String context = indexed.buildContext("s1", "what is my name", 2, 500);
        assertThat(context).contains("Alice");
        assertThat(index.searchCalls.get()).isGreaterThanOrEqualTo(1);

        // A cold index (new process / new collection) is rebuilt from SQL before the search.
        FakeIndex cold = new FakeIndex();
        SessionMemoryStore restarted = new SessionMemoryStore(repository,
                new HashEmbeddingProvider(), vectors(cold), "latest", "vector");
        String restored = restarted.buildContext("s1", "what is my name", 2, 500);

        assertThat(cold.points).hasSize(3);
        assertThat(restored).contains("Alice");
    }

    @Test
    void vectorStoreFailureFallsBackToInMemoryRecall() {
        FakeIndex index = new FakeIndex();
        index.fail = true;
        SessionMemoryStore failing = new SessionMemoryStore(repository,
                new HashEmbeddingProvider(), vectors(index), "latest", "vector");
        failing.ensureSession("s1", "tenant-a");
        failing.writeFact("s1", "user_name", "Alice", "task-1");
        failing.writeFact("s1", "food", "noodles", "task-2");

        String context = failing.buildContext("s1", "what is my name", 3, 500);

        assertThat(context).contains("Alice");
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<com.forgeflow.store.vector.FactVectorStore>
    vectors(com.forgeflow.store.vector.FactVectorStore store) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public com.forgeflow.store.vector.FactVectorStore getObject() {
                return store;
            }

            @Override
            public com.forgeflow.store.vector.FactVectorStore getObject(Object... args) {
                return store;
            }

            @Override
            public com.forgeflow.store.vector.FactVectorStore getIfAvailable() {
                return store;
            }

            @Override
            public com.forgeflow.store.vector.FactVectorStore getIfUnique() {
                return store;
            }
        };
    }

    /** In-memory stand-in for Qdrant: stores vectors and ranks by cosine. */
    static final class FakeIndex implements com.forgeflow.store.vector.FactVectorStore {
        record Point(String sessionId, String id, String factKey, float[] vector) {
        }

        final java.util.List<Point> points = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger searchCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean fail;

        @Override
        public void index(String model, int dim, String sessionId, String factId, String factKey,
                          float[] vector) {
            points.removeIf(point -> point.id().equals(factId));
            points.add(new Point(sessionId, factId, factKey, vector));
        }

        @Override
        public java.util.List<String> search(String model, int dim, String sessionId,
                                             float[] query, int limit) {
            searchCalls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("qdrant down");
            }
            return points.stream()
                    .filter(point -> point.sessionId().equals(sessionId))
                    .sorted(java.util.Comparator.comparingDouble(
                            (Point point) -> TextVectorizer.cosine(query, point.vector())).reversed())
                    .limit(limit)
                    .map(Point::id)
                    .toList();
        }

        @Override
        public long count(String model, int dim, String sessionId) {
            return points.stream().filter(point -> point.sessionId().equals(sessionId)).count();
        }
    }

    @Test
    void taskBindingResolvesSession() {
        store.ensureSession("s1", "tenant-a");
        store.bindTask("task-1", "s1");

        assertThat(store.sessionForTask("task-1")).contains("s1");
        assertThat(store.sessionForTask("missing")).isEmpty();
    }

    @Test
    void tenantMismatchIsRejected() {
        store.ensureSession("s1", "tenant-a");

        assertThatThrownBy(() -> store.ensureSession("s1", "tenant-b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another tenant");
    }

    @Test
    void contextRespectsCharacterBudget() {
        store.ensureSession("s1", "tenant-a");
        store.writeSummary("s1", "x".repeat(5000), null);
        store.writeFact("s1", "name", "Alice", "task-1");

        String context = store.buildContext("s1", 10, 600);
        assertThat(context.length()).isLessThanOrEqualTo(620);
        assertThat(context).contains("Alice");
    }

    @Test
    void unknownSessionHasNoSnapshotOrContext() {
        assertThat(store.snapshot("nope")).isEqualTo(Optional.empty());
        assertThat(store.buildContext("nope", 10, 500)).isEmpty();
    }
}
