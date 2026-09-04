package ru.safeai.gateway.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@ConfigurationProperties(
        prefix = "safeai.knowledge.embedding"
)
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

    private static final String OPENAI_URL =
            "https://api.openai.com/v1";

    private static final String HASHING_PROVIDER =
            "hashing";

    private static final String OPENAI_PROVIDER =
            "openai";

    private static final String DEFAULT_OPENAI_MODEL =
            "text-embedding-3-small";

    private static final Set<String> SUPPORTED_PROVIDERS =
            Set.of(
                    HASHING_PROVIDER,
                    OPENAI_PROVIDER
            );

    public KnowledgeEmbeddingProperties {
        provider =
                provider == null
                        || provider.isBlank()
                        ? HASHING_PROVIDER
                        : provider.strip()
                                .toLowerCase(
                                        Locale.ROOT
                                );

        if (!SUPPORTED_PROVIDERS.contains(
                provider
        )) {
            throw invalid(
                    "provider"
            );
        }

        dimensions =
                dimensions == null
                        ? 384
                        : dimensions;

        if (dimensions != 384) {
            throw invalid(
                    "dimensions"
            );
        }

        batchSize =
                bounded(
                        batchSize,
                        64,
                        1,
                        256,
                        "batch-size"
                );

        maxInputChars =
                bounded(
                        maxInputChars,
                        20_000,
                        100,
                        100_000,
                        "max-input-chars"
                );

        connectTimeout =
                positive(
                        connectTimeout,
                        Duration.ofSeconds(
                                5
                        ),
                        "connect-timeout"
                );

        readTimeout =
                positive(
                        readTimeout,
                        Duration.ofSeconds(
                                30
                        ),
                        "read-timeout"
                );

        if (OPENAI_PROVIDER.equals(
                provider
        )) {
            baseUrl =
                    baseUrl == null
                            || baseUrl.isBlank()
                            ? OPENAI_URL
                            : baseUrl.strip();

            URI uri =
                    parseUri(
                            baseUrl
                    );

            /*
             * Embedding endpoint intentionally pinned to the official
             * OpenAI API endpoint.
             *
             * Do not broaden this to arbitrary hosts without introducing
             * an explicit outbound allow-list / SSRF policy.
             */
            if (!"https".equalsIgnoreCase(
                    uri.getScheme()
            )
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !OPENAI_URL.equals(
                            baseUrl
                    )) {
                throw invalid(
                        "base-url"
                );
            }

            if (apiKey == null
                    || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "OPENAI_EMBEDDING_API_KEY обязателен "
                                + "для production embedding"
                );
            }

            apiKey =
                    apiKey.strip();

            model =
                    model == null
                            || model.isBlank()
                            ? DEFAULT_OPENAI_MODEL
                            : model.strip();

        } else {
            /*
             * Local deterministic provider must not retain irrelevant
             * endpoint configuration or production credentials.
             */
            baseUrl =
                    null;

            apiKey =
                    null;

            model =
                    HashingDefaults.MODEL;
        }
    }

    /**
     * Configuration contains an API credential and therefore must never rely
     * on the record-generated toString(), because that would expose apiKey.
     */
    @Override
    @SuppressWarnings("NullableProblems")
    public String toString() {
        return "KnowledgeEmbeddingProperties["
                + "provider=" + provider
                + ", baseUrl=" + baseUrl
                + ", apiKey=<redacted>"
                + ", model=" + model
                + ", dimensions=" + dimensions
                + ", batchSize=" + batchSize
                + ", maxInputChars=" + maxInputChars
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + "]";
    }

    private static URI parseUri(
            String value
    ) {
        try {
            return URI.create(
                    value
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Некорректное значение "
                            + "safeai.knowledge.embedding.base-url",
                    exception
            );
        }
    }

    private static int bounded(
            Integer value,
            int fallback,
            int minimum,
            int maximum,
            String name
    ) {
        int resolved =
                value == null
                        ? fallback
                        : value;

        if (resolved < minimum
                || resolved > maximum) {
            throw invalid(
                    name
            );
        }

        return resolved;
    }

    private static Duration positive(
            Duration value,
            Duration fallback,
            String name
    ) {
        Duration resolved =
                value == null
                        ? fallback
                        : value;

        if (resolved.isZero()
                || resolved.isNegative()) {
            throw invalid(
                    name
            );
        }

        return resolved;
    }

    private static IllegalStateException invalid(
            String name
    ) {
        return new IllegalStateException(
                "Некорректное значение "
                        + "safeai.knowledge.embedding."
                        + name
        );
    }

    private static final class HashingDefaults {

        private static final String MODEL =
                "safeai-feature-hash-v1";

        private HashingDefaults() {
        }
    }
}