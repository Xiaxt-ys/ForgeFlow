package com.forgeflow.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextVectorizerTest {

    @Test
    void relatedTextsShareDirection() {
        double score = TextVectorizer.similarity("what is my name", "user name is Alice");

        assertThat(score).isGreaterThan(0.1);
    }

    @Test
    void unrelatedTextsAreNearlyOrthogonal() {
        double score = TextVectorizer.similarity("what is my name", "deployment region is eu-west");

        assertThat(score).isLessThan(0.1);
    }

    @Test
    void chineseBigramsGiveSimilarity() {
        double score = TextVectorizer.similarity("我的姓名是什么", "用户姓名是张三");

        assertThat(score).isGreaterThan(0.05);
    }

    @Test
    void encodingRoundTrips() {
        float[] vector = TextVectorizer.vectorize("Alice works on ForgeFlow");
        float[] decoded = TextVectorizer.decode(TextVectorizer.encode(vector));

        assertThat(decoded).hasSize(TextVectorizer.DIM);
        assertThat(TextVectorizer.cosine(vector, decoded)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void decodingKeepsTheStoredDimension() {
        // An external provider may return any dimension; padding it to 256 would make every
        // stored vector invisible to cosine (length mismatch).
        assertThat(TextVectorizer.decode("0.1,0.2,0.3")).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void emptyTextYieldsAZeroVector() {
        assertThat(TextVectorizer.vectorize("")).containsOnly(0f);
        assertThat(TextVectorizer.cosine(TextVectorizer.vectorize(""), TextVectorizer.vectorize("x")))
                .isZero();
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
