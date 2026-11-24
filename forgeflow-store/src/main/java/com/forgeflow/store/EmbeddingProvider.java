package com.forgeflow.store;

import java.util.List;

/**
 * Turns text into vectors for memory recall.
 *
 * <p>An {@link Embedding} carries the model id that produced it: vectors from different models
 * are not comparable, and the fact row stores that id next to the vector so a provider switch
 * can be detected and repaired instead of silently scoring garbage.</p>
 *
 * <p>Implementations are resilient by contract — an embedding outage must never make a task
 * fail or a recall empty-handed; the HTTP provider falls back to the local hashing vectoriser.</p>
 */
public interface EmbeddingProvider {

    /** The model this provider is configured to use (may differ from what a call returns). */
    String model();

    /** Vectors for the given texts, all in one space, with the model id that actually ran. */
    Embedding embed(List<String> texts);

    /** Single-text convenience; keeps the model id that actually ran. */
    default Embedding embedOne(String text) {
        Embedding embedding = embed(List.of(text));
        return new Embedding(embedding.model(), List.of(embedding.first()));
    }
}
