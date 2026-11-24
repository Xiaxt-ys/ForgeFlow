package com.forgeflow.store;

/** Minimal fact identity for re-indexing (the ANN payload and point id). */
public record FactRef(String id, String factKey, String factValue) {
}
