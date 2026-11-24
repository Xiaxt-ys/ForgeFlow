package com.forgeflow.store;

import java.util.List;

/** Vectors plus the model id that produced them. */
public record Embedding(String model, List<float[]> vectors) {

    public float[] first() {
        return vectors.isEmpty() ? new float[0] : vectors.get(0);
    }

    public int dim() {
        return first().length;
    }
}
