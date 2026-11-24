package com.forgeflow.store.vector;

import java.util.List;

/**
 * Optional ANN index over session facts.
 *
 * <p>The relational row stays the source of truth: pointers returned by {@link #search} are
 * joined back against {@code ff_session_fact} (ACTIVE only), so a stale or orphan vector can
 * never resurface an archived fact. The index is an acceleration layer, not a second store.</p>
 */
public interface FactVectorStore {

    /** Indexes (or re-indexes) one fact. Collection is derived from the model and dimension. */
    void index(String model, int dim, String sessionId, String factId, String factKey,
               float[] vector);

    /** Nearest fact ids for this session, most similar first. */
    List<String> search(String model, int dim, String sessionId, float[] query, int limit);

    /** How many vectors this session currently has in the index. */
    long count(String model, int dim, String sessionId);
}
