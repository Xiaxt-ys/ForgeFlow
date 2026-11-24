package com.forgeflow.store;

/** An active fact plus the vector stored alongside it and the model that produced it. */
public record FactCandidate(SessionFact fact, float[] embedding, String model) {
}
