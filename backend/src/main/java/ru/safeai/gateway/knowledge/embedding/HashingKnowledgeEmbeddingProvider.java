package ru.safeai.gateway.knowledge.embedding;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Deterministic, offline feature-hashing embedding. It keeps local/demo
 * retrieval fully functional and reproducible. Production semantic providers
 * implement the same interface and use a distinct immutable model name.
 */
@Component
@ConditionalOnProperty(
        name = "safeai.knowledge.embedding.provider",
        havingValue = "hashing",
        matchIfMissing = true
)
public class HashingKnowledgeEmbeddingProvider
        implements KnowledgeEmbeddingProvider {

    public static final int DIMENSIONS = 384;
    public static final String MODEL = "safeai-feature-hash-v1";

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        String normalized = Normalizer.normalize(
                text == null ? "" : text,
                Normalizer.Form.NFKC
        ).toLowerCase(Locale.ROOT);

        normalized.lines().forEach(line -> {
            String[] tokens = line.split("[^\\p{L}\\p{N}]+", -1);
            for (String token : tokens) {
                if (!token.isBlank()) {
                    addFeature(vector, "w:" + token, 1.0f);
                    addCharacterNgrams(vector, token);
                }
            }
        });

        normalize(vector);
        return vector;
    }

    private static void addCharacterNgrams(float[] vector, String token) {
        String bounded = '^' + token + '$';
        for (int size = 3; size <= 5; size++) {
            if (bounded.length() < size) {
                continue;
            }
            for (int start = 0;
                    start + size <= bounded.length();
                    start++) {
                addFeature(
                        vector,
                        "c:" + bounded.substring(start, start + size),
                        0.35f
                );
            }
        }
    }

    private static void addFeature(
            float[] vector,
            String feature,
            float weight
    ) {
        byte[] digest = sha256(feature);
        int raw = ((digest[0] & 0xff) << 24)
                | ((digest[1] & 0xff) << 16)
                | ((digest[2] & 0xff) << 8)
                | (digest[3] & 0xff);
        int index = Math.floorMod(raw, vector.length);
        float direction = (digest[4] & 1) == 0 ? 1.0f : -1.0f;
        vector[index] += direction * weight;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void normalize(float[] vector) {
        double squareSum = 0.0;
        for (float value : vector) {
            squareSum += value * value;
        }
        if (squareSum == 0.0) {
            return;
        }
        double length = Math.sqrt(squareSum);
        for (int index = 0; index < vector.length; index++) {
            vector[index] = (float) (vector[index] / length);
        }
    }
}
