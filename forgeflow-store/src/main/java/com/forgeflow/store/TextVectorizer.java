package com.forgeflow.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dependency-free text vectoriser for memory recall.
 *
 * <p>Tokens are hashed into a fixed 256-dimension space with signed feature hashing, so the
 * same text always yields the same vector and no embedding service is needed. ASCII words and
 * CJK character bigrams are both indexed, which is enough for "which stored facts are related
 * to this request" — it is not a semantic embedding and does not generalise across synonyms.</p>
 */
public final class TextVectorizer {

    public static final int DIM = 256;
    private static final double MIN_COSINE = 0.03;

    /**
     * Function words carry no topic. Without this filter "what is my name" and "deployment
     * region is eu-west" look 0.22 similar purely because both contain "is".
     */
    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "am",
            "of", "to", "in", "on", "at", "for", "with", "as", "by", "from", "into", "about",
            "and", "or", "but", "if", "then", "than", "so", "not", "no",
            "i", "me", "my", "mine", "you", "your", "yours", "we", "our", "they", "their",
            "he", "she", "it", "its", "this", "that", "these", "those",
            "what", "which", "who", "whom", "whose", "when", "where", "why", "how",
            "do", "does", "did", "done", "have", "has", "had", "can", "could", "would",
            "should", "will", "shall", "may", "might", "please", "tell", "give");

    private TextVectorizer() {
    }

    public static float[] vectorize(String text) {
        float[] vector = new float[DIM];
        if (text == null || text.isBlank()) {
            return vector;
        }
        for (String token : tokens(text.toLowerCase(Locale.ROOT))) {
            if (STOPWORDS.contains(token)) {
                continue;
            }
            int hash = fnv(token);
            int index = hash & (DIM - 1);
            int sign = ((hash >>> 16) & 1) == 0 ? 1 : -1;
            vector[index] += sign;
        }
        // L2 normalise so cosine is a plain dot product.
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm > 0) {
            double scale = 1 / Math.sqrt(norm);
            for (int i = 0; i < DIM; i++) {
                vector[i] *= (float) scale;
            }
        }
        return vector;
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    public static double similarity(String query, String text) {
        return cosine(vectorize(query), vectorize(text));
    }

    public static boolean related(String query, String text) {
        return similarity(query, text) >= MIN_COSINE;
    }

    /** Compact wire form stored in the fact row: comma-separated floats. */
    public static String encode(float[] vector) {
        if (vector == null) {
            return "";
        }
        StringBuilder encoded = new StringBuilder(vector.length * 8);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                encoded.append(',');
            }
            encoded.append(Float.toString(vector[i]));
        }
        return encoded.toString();
    }

    public static float[] decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return new float[DIM];
        }
        String[] parts = encoded.split(",");
        // The stored dimension is whatever the provider produced — do not force it to DIM,
        // otherwise an external 64/1536-dim vector would be silently padded and never match.
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                vector[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException ignored) {
                vector[i] = 0f;
            }
        }
        return vector;
    }

    static List<String> tokens(String lower) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        StringBuilder han = new StringBuilder();
        for (int i = 0; i < lower.length(); ) {
            int codePoint = lower.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                flushWord(word, tokens);
                han.appendCodePoint(codePoint);
            } else if (Character.isLetterOrDigit(codePoint) && codePoint < 128) {
                flushHan(han, tokens);
                word.appendCodePoint(codePoint);
            } else {
                flushWord(word, tokens);
                flushHan(han, tokens);
            }
            i += width;
        }
        flushWord(word, tokens);
        flushHan(han, tokens);
        return tokens;
    }

    private static void flushWord(StringBuilder word, List<String> tokens) {
        if (word.length() > 0) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private static void flushHan(StringBuilder han, List<String> tokens) {
        if (han.length() == 0) {
            return;
        }
        String run = han.toString();
        for (int i = 0; i < run.length(); i++) {
            tokens.add(run.substring(i, i + 1));
            if (i + 1 < run.length()) {
                tokens.add(run.substring(i, i + 2));
            }
        }
        han.setLength(0);
    }

    private static int fnv(String token) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }
}
