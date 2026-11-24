package com.forgeflow.store;

import com.forgeflow.store.vector.FactVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The memory read/write facade used by the entry and exit agents.
 *
 * <p>Recall is relevance-based, not "newest N": {@code vector} ranks stored facts by cosine
 * similarity to the incoming prompt, {@code hybrid} (default) always carries the newest few as
 * anchors and fills the rest by similarity. Every fact that is actually injected is reinforced
 * ({@code hits++}, {@code last_used_at=now}), which is what protects it from decay.</p>
 *
 * <p>Context is deliberately compact: a rolling summary plus selected facts, capped by a
 * character budget. Sub-agents never read this store directly; the planner decides what to
 * carry into their prompts.</p>
 */
@Component
public class SessionMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryStore.class);

    private final SessionRepository repository;
    private final EmbeddingProvider embedding;
    private final ObjectProvider<FactVectorStore> vectorStores;
    private final String conflictPolicy;
    private final String recallMode;
    private final Set<String> indexedSessions = ConcurrentHashMap.newKeySet();

    @Autowired
    public SessionMemoryStore(SessionRepository repository, EmbeddingProvider embedding,
                              ObjectProvider<FactVectorStore> vectorStores,
                              @Value("${forgeflow.memory.conflict:latest}") String conflictPolicy,
                              @Value("${forgeflow.memory.recall:hybrid}") String recallMode) {
        this.repository = repository;
        this.embedding = embedding == null ? new HashEmbeddingProvider() : embedding;
        this.vectorStores = vectorStores;
        this.conflictPolicy = conflictPolicy == null ? "latest" : conflictPolicy.trim().toLowerCase();
        this.recallMode = recallMode == null ? "hybrid" : recallMode.trim().toLowerCase();
    }

    public SessionMemoryStore(SessionRepository repository, EmbeddingProvider embedding,
                              String conflictPolicy, String recallMode) {
        this(repository, embedding, null, conflictPolicy, recallMode);
    }

    public SessionMemoryStore(SessionRepository repository, String conflictPolicy, String recallMode) {
        this(repository, new HashEmbeddingProvider(), null, conflictPolicy, recallMode);
    }

    public SessionMemoryStore(SessionRepository repository) {
        this(repository, new HashEmbeddingProvider(), null, "latest", "hybrid");
    }

    /** Stable block format so the model can tell memory apart from the current request. */
    public String buildContext(String sessionId, int maxFacts, int maxChars) {
        return buildContext(sessionId, null, maxFacts, maxChars);
    }

    public String buildContext(String sessionId, String query, int maxFacts, int maxChars) {
        if (sessionId == null) {
            return "";
        }
        Optional<SessionSummary> summary = repository.summary(sessionId);
        List<SessionFact> facts;
        try {
            facts = select(sessionId, query, maxFacts);
        } catch (RuntimeException failure) {
            // Recall is an enhancement: never fail an entry read because embeddings misbehaved.
            log.warn("memory.recall failed session={}: {} — falling back to newest facts",
                    sessionId, failure.getMessage());
            facts = repository.facts(sessionId, maxFacts);
        }
        if (summary.isEmpty() && facts.isEmpty()) {
            return "";
        }
        if (!facts.isEmpty()) {
            repository.touch(sessionId, facts.stream().map(SessionFact::factKey).toList());
        }

        StringBuilder factBlock = new StringBuilder();
        for (SessionFact fact : facts) {
            factBlock.append("- ").append(fact.factKey()).append(": ").append(fact.factValue()).append('\n');
        }

        int budget = Math.max(200, maxChars);
        String header = "[session memory]\n";
        String footer = "[/session memory]\n\n";
        int factsBudget = Math.max(0, budget - header.length() - footer.length() - 40);
        String factsText = factBlock.length() > factsBudget
                ? factBlock.substring(0, factsBudget) + "\n"
                : factBlock.toString();
        int summaryBudget = Math.max(0, budget - header.length() - footer.length()
                - factsText.length() - "Summary: \nFacts:\n".length());
        String summaryText = summary.map(SessionSummary::summary).orElse("");
        if (summaryText.length() > summaryBudget) {
            // Keep the tail: the newest turns matter more than the beginning of a session.
            summaryText = summaryBudget <= 1 ? ""
                    : "…" + summaryText.substring(summaryText.length() - summaryBudget + 1);
        }

        StringBuilder context = new StringBuilder(header);
        if (!summaryText.isBlank()) {
            context.append("Summary: ").append(summaryText).append('\n');
        }
        if (!factsText.isBlank()) {
            context.append("Facts:\n").append(factsText);
        }
        context.append(footer);
        return context.toString();
    }

    /**
     * Picks the facts worth injecting.
     *
     * <ul>
     *   <li>{@code recent}: newest first, the pre-lifecycle behaviour;</li>
     *   <li>{@code vector}: cosine similarity only, unrelated facts dropped;</li>
     *   <li>{@code hybrid}: newest few as anchors, then the most similar remaining facts.</li>
     * </ul>
     */
    private List<SessionFact> select(String sessionId, String query, int maxFacts) {
        List<FactCandidate> candidates = repository.candidates(sessionId, Math.max(maxFacts * 5, 50));
        if (candidates.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, maxFacts);
        if ("recent".equals(recallMode) || query == null || query.isBlank()) {
            return candidates.stream().limit(limit).map(FactCandidate::fact).toList();
        }

        List<SessionFact> viaIndex = vectorSelect(sessionId, query, limit);
        if (viaIndex != null && !viaIndex.isEmpty()) {
            return viaIndex;
        }

        List<float[]> vectors = vectorsFor(sessionId, query, candidates);
        float[] queryVector = vectors.get(0);
        boolean hashed = HashEmbeddingProvider.MODEL.equals(embeddingModelUsed);

        Instant now = Instant.now();
        record Ranked(FactCandidate candidate, float[] vector, double cosine, double score) {
        }
        List<Ranked> ranked = new ArrayList<>();
        double best = 0;
        for (int i = 0; i < candidates.size(); i++) {
            FactCandidate candidate = candidates.get(i);
            SessionFact fact = candidate.fact();
            float[] vector = vectors.get(i + 1);
            double cosine = TextVectorizer.cosine(queryVector, vector);
            best = Math.max(best, cosine);
            double recency = Math.exp(-Duration.between(fact.updatedAt(), now).toHours() / 720.0);
            ranked.add(new Ranked(candidate, vector, cosine,
                    0.75 * cosine + 0.15 * fact.importance() + 0.10 * recency));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        // Semantic models compress cosine into a narrow band (bge-m3: related ~0.50,
        // unrelated ~0.41), so an absolute cutoff is meaningless there — keep what is close
        // to the best hit instead. The hashing vectoriser is sparse and needs an absolute one.
        double threshold = hashed ? 0.03 : Math.max(0.05, best * 0.9);

        List<SessionFact> chosen = new ArrayList<>();
        if (!"vector".equals(recallMode)) {
            // Anchors: identity/decision facts must not depend on how the user phrases the ask.
            // Keep anchors to three and always leave at least one slot for similarity.
            int anchors = Math.min(3, Math.max(1, limit - 1));
            candidates.stream().limit(anchors).forEach(c -> chosen.add(c.fact()));
        }
        for (Ranked candidate : ranked) {
            if (chosen.size() >= limit) {
                break;
            }
            SessionFact fact = candidate.candidate().fact();
            if (candidate.cosine() < threshold) {
                continue;
            }
            boolean already = chosen.stream()
                    .anyMatch(existing -> existing.factKey().equals(fact.factKey()));
            if (!already) {
                chosen.add(fact);
            }
        }
        return chosen.isEmpty()
                ? candidates.stream().limit(limit).map(FactCandidate::fact).toList()
                : chosen;
    }

    /**
     * Recalls through the optional ANN index. Returns {@code null} when there is no index or it
     * failed — the caller then uses the in-memory cosine scan, so memory keeps working with
     * Qdrant down.
     */
    private List<SessionFact> vectorSelect(String sessionId, String query, int limit) {
        FactVectorStore index = vectorStores == null ? null : vectorStores.getIfAvailable();
        if (index == null) {
            return null;
        }
        try {
            Embedding queryEmbedding = embedding.embedOne(query);
            String model = queryEmbedding.model();
            int dim = queryEmbedding.dim();
            String cacheKey = model + "|" + sessionId;
            if (indexedSessions.add(cacheKey)) {
                try {
                    repairIndex(sessionId, index, model, dim);
                } catch (RuntimeException failure) {
                    indexedSessions.remove(cacheKey);
                    throw failure;
                }
            }
            List<SessionFact> chosen = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            if (!"vector".equals(recallMode)) {
                int anchors = Math.min(3, Math.max(1, limit - 1));
                for (SessionFact fact : repository.facts(sessionId, anchors)) {
                    if (keys.add(fact.factKey())) {
                        chosen.add(fact);
                    }
                }
            }
            List<String> ids = index.search(model, dim, sessionId, queryEmbedding.first(),
                    Math.max(limit * 2, 10));
            for (SessionFact fact : repository.factsByIds(sessionId, ids)) {
                if (chosen.size() >= limit) {
                    break;
                }
                if (keys.add(fact.factKey())) {
                    chosen.add(fact);
                }
            }
            return chosen;
        } catch (RuntimeException failure) {
            log.warn("memory.vector-store failed session={}: {} — falling back to in-memory recall",
                    sessionId, failure.getMessage());
            return null;
        }
    }

    /** One-time per session and process: makes the index match the SQL truth after a cold start. */
    private void repairIndex(String sessionId, FactVectorStore index, String model, int dim) {
        List<FactRef> active = repository.factRefs(sessionId, 500);
        if (active.isEmpty()) {
            return;
        }
        if (index.count(model, dim, sessionId) >= active.size()) {
            return;
        }
        for (int start = 0; start < active.size(); start += 64) {
            List<FactRef> chunk = active.subList(start, Math.min(start + 64, active.size()));
            List<String> texts = chunk.stream()
                    .map(ref -> ref.factKey() + " " + ref.factValue())
                    .toList();
            Embedding embedded = embedding.embed(texts);
            if (!embedded.model().equals(model)) {
                // The provider flipped spaces mid-repair; skip and let recall use the fallback.
                return;
            }
            for (int i = 0; i < chunk.size(); i++) {
                index.index(model, dim, sessionId, chunk.get(i).id(),
                        chunk.get(i).factKey(), embedded.vectors().get(i));
            }
        }
        log.info("memory.vector-store reindexed session={} facts={} model={}",
                sessionId, active.size(), model);
    }

    /** Model that produced the vectors used by the last {@link #vectorsFor} call. */
    private volatile String embeddingModelUsed = HashEmbeddingProvider.MODEL;

    /**
     * Returns {@code [queryVector, factVector...]}, all in one space.
     *
     * <p>Stored vectors are reused only when they came from the model that answered the query.
     * After a provider switch (or after an outage downgraded to the hash vectoriser) the whole
     * candidate set is re-embedded in one batch and written back, so the repair happens once
     * instead of on every recall.</p>
     */
    private List<float[]> vectorsFor(String sessionId, String query, List<FactCandidate> candidates) {
        Embedding queryEmbedding = embedding.embedOne(query);
        String model = queryEmbedding.model();
        boolean stale = candidates.stream()
                .anyMatch(candidate -> candidate.model() == null
                        || !candidate.model().equals(model));
        List<float[]> vectors = new ArrayList<>(candidates.size() + 1);
        if (!stale) {
            embeddingModelUsed = model;
            vectors.add(queryEmbedding.first());
            for (FactCandidate candidate : candidates) {
                vectors.add(candidate.embedding());
            }
            return vectors;
        }

        List<String> texts = new ArrayList<>(candidates.size() + 1);
        texts.add(query);
        for (FactCandidate candidate : candidates) {
            texts.add(candidate.fact().factKey() + " " + candidate.fact().factValue());
        }
        Embedding batch = embedding.embed(texts);
        embeddingModelUsed = batch.model();
        vectors.add(batch.first());
        for (int i = 0; i < candidates.size(); i++) {
            float[] vector = batch.vectors().get(i + 1);
            vectors.add(vector);
            try {
                repository.updateEmbedding(sessionId, candidates.get(i).fact().factKey(),
                        vector, batch.model());
            } catch (RuntimeException ignored) {
                // A read path must not fail on a best-effort vector repair.
            }
        }
        return vectors;
    }

    public Optional<SessionSnapshot> snapshot(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        Optional<String> tenant = repository.tenantOf(sessionId);
        if (tenant.isEmpty()) {
            return Optional.empty();
        }
        String summary = repository.summary(sessionId).map(SessionSummary::summary).orElse(null);
        return Optional.of(new SessionSnapshot(sessionId, tenant.get(), summary,
                repository.facts(sessionId, 50)));
    }

    public void writeSummary(String sessionId, String summary, String structuredState) {
        if (sessionId != null && summary != null && !summary.isBlank()) {
            repository.saveSummary(sessionId, summary, structuredState);
        }
    }

    public void writeFact(String sessionId, String key, String value, String sourceTaskId) {
        if (sessionId == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        Optional<FactWrite> write = repository.upsertFact(sessionId, key.trim(), value.trim(),
                sourceTaskId, "keep-both".equals(conflictPolicy), embedding);
        if (write.isEmpty() || !write.get().hasVector()) {
            return;
        }
        FactVectorStore index = vectorStores == null ? null : vectorStores.getIfAvailable();
        if (index == null) {
            return;
        }
        try {
            index.index(write.get().model(), write.get().embedding().length, sessionId,
                    write.get().id(), key.trim(), write.get().embedding());
        } catch (RuntimeException failure) {
            // The relational row is the source of truth; the index can be rebuilt later.
            log.warn("memory.vector-store index failed session={} key={}: {}",
                    sessionId, key, failure.getMessage());
        }
    }

    public List<FactHistory> history(String sessionId, String key, int limit) {
        return repository.history(sessionId, key, limit);
    }

    public void ensureSession(String sessionId, String tenantId) {
        if (sessionId != null && !sessionId.isBlank()) {
            repository.ensureSession(sessionId, tenantId);
        }
    }

    public void bindTask(String taskId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            repository.bindTask(taskId, sessionId);
        }
    }

    public Optional<String> sessionForTask(String taskId) {
        return repository.sessionForTask(taskId);
    }
}
