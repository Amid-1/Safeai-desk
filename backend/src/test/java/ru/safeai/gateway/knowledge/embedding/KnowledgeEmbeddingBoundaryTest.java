package ru.safeai.gateway.knowledge.embedding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class KnowledgeEmbeddingBoundaryTest {
    private final HashingKnowledgeEmbeddingProvider hashing = new HashingKnowledgeEmbeddingProvider();

    @Test
    void localEmbeddingIsDeterministicUnicodeNormalizedAndUnitLength() {
        float[] first = hashing.embed("Склад   ТЕСТ\nMünchen");
        float[] second = hashing.embed("склад тест\nmünchen");
        assertThat(first).containsExactly(second);
        assertThat(first).hasSize(384);
        double norm = Math.sqrt(Arrays.stream(toDouble(first)).map(v -> v * v).sum());
        assertThat(norm).isCloseTo(1d, org.assertj.core.data.Offset.offset(.0001));
    }

    @Test
    void hashingDoesNotReturnNaNForEmptyOrWhitespaceInput() {
        float[] vector = hashing.embed("   \n");
        assertThat(vector).hasSize(384).containsOnly(0f);
        assertThat(hashing.model()).isEqualTo(HashingKnowledgeEmbeddingProvider.MODEL);
    }

    @Test
    void pgVectorRejectsNullEmptyAndNonFiniteCoordinates() {
        assertInvalidVector(null);
        assertInvalidVector(new float[0]);
        assertInvalidVector(new float[] {Float.NaN});
        assertInvalidVector(new float[] {Float.POSITIVE_INFINITY});
        assertThat(PgVectorSupport.encode(new float[] {0f, -1f, .5f}))
                .isEqualTo("[0.0,-1.0,0.5]");
    }

    @Test
    void embeddedChunkCopiesIncomingAndOutgoingArrays() {
        var chunk = new ru.safeai.gateway.knowledge.chunking.KnowledgeChunkCandidate(
                0, "content", "a".repeat(64), 2, 1, 1, null);
        float[] original = new float[] {1f, 2f};
        var result = new ru.safeai.gateway.knowledge.ingestion.EmbeddedKnowledgeChunk(chunk, original);
        original[0] = 99f;
        result.embedding()[1] = 88f;
        assertThat(result.embedding()).containsExactly(1f, 2f);
    }

    private static void assertInvalidVector(float[] vector) {
        assertThatThrownBy(() -> PgVectorSupport.encode(vector))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double[] toDouble(float[] source) {
        double[] values = new double[source.length];
        for (int i = 0; i < source.length; i++) values[i] = source[i];
        return values;
    }
}
