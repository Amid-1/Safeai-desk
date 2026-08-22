package ru.safeai.gateway.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@ConfigurationProperties(prefix = "safeai.knowledge.embedding")
public record KnowledgeEmbeddingProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        Integer dimensions,
        Integer batchSize,
        Integer maxInputChars,
        Duration connectTimeout,
        Duration readTimeout
) {
    private static final String OPENAI_URL = "https://api.openai.com/v1";

    public KnowledgeEmbeddingProperties {
        provider = provider == null || provider.isBlank()
                ? "hashing"
                : provider.strip().toLowerCase(Locale.ROOT);
        if (!Set.of("hashing", "openai").contains(provider)) {
            throw invalid("provider");
        }
        dimensions = dimensions == null ? 384 : dimensions;
        if (dimensions != 384) {
            throw invalid("dimensions");
        }
        batchSize = bounded(batchSize, 64, 1, 256, "batch-size");
        maxInputChars = bounded(
                maxInputChars,
                20_000,
                100,
                100_000,
                "max-input-chars"
        );
        connectTimeout = positive(
                connectTimeout,
                Duration.ofSeconds(5),
                "connect-timeout"
        );
        readTimeout = positive(
                readTimeout,
                Duration.ofSeconds(30),
                "read-timeout"
        );
        if ("openai".equals(provider)) {
            baseUrl = baseUrl == null || baseUrl.isBlank()
                    ? OPENAI_URL
                    : baseUrl.strip();
            URI uri = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !OPENAI_URL.equals(baseUrl)) {
                throw invalid("base-url");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "OPENAI_EMBEDDING_API_KEY обязателен для production embedding"
                );
            }
            apiKey = apiKey.strip();
            model = model == null || model.isBlank()
                    ? "text-embedding-3-small"
                    : model.strip();
        } else {
            baseUrl = null;
            apiKey = null;
            model = HashingDefaults.MODEL;
        }
    }

    private static int bounded(
            Integer value,
            int fallback,
            int min,
            int max,
            String name
    ) {
        int resolved = value == null ? fallback : value;
        if (resolved < min || resolved > max) {
            throw invalid(name);
        }
        return resolved;
    }

    private static Duration positive(
            Duration value,
            Duration fallback,
            String name
    ) {
        Duration resolved = value == null ? fallback : value;
        if (resolved.isZero() || resolved.isNegative()) {
            throw invalid(name);
        }
        return resolved;
    }

    private static IllegalStateException invalid(String name) {
        return new IllegalStateException(
                "Некорректное значение safeai.knowledge.embedding." + name
        );
    }

    private static final class HashingDefaults {
        private static final String MODEL = "safeai-feature-hash-v1";
    }
}
