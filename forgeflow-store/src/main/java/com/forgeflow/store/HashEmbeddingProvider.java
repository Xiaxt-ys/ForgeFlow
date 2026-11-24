package com.forgeflow.store;

import java.util.ArrayList;
import java.util.List;

/** Offline provider: signed feature hashing over words and CJK bigrams. Always available. */
public class HashEmbeddingProvider implements EmbeddingProvider {

    public static final String MODEL = "hash-256";

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public Embedding embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(TextVectorizer.vectorize(text));
        }
        return new Embedding(MODEL, vectors);
    }
}
