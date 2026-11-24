package com.forgeflow.store;

/**
 * Result of a fact write: the row id, plus the vector/model when the value changed (null on a
 * touch, where no re-embedding happened). Used to keep the optional ANN index in sync.
 */
public record FactWrite(String id, String model, float[] embedding) {

    public boolean hasVector() {
        return embedding != null && model != null;
    }
}
