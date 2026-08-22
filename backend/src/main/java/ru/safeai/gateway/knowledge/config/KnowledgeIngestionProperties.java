package ru.safeai.gateway.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.knowledge.ingestion")
public record KnowledgeIngestionProperties(
        Boolean enabled,
        Duration pollDelay,
        Integer batchSize,
        Duration processingLease,
        Duration extractionTimeout,
        Integer extractionThreads,
        Integer maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Integer maxExtractedChars,
        Long maxDocxUncompressedBytes,
        Integer chunkSizeChars,
        Integer chunkOverlapChars
) {

    public KnowledgeIngestionProperties {
        enabled = enabled == null || enabled;
        pollDelay = defaultDuration(pollDelay, Duration.ofSeconds(2));
        processingLease = defaultDuration(
                processingLease,
                Duration.ofMinutes(3)
        );
        extractionTimeout = defaultDuration(
                extractionTimeout,
                Duration.ofSeconds(60)
        );
        initialBackoff = defaultDuration(
                initialBackoff,
                Duration.ofSeconds(10)
        );
        maxBackoff = defaultDuration(maxBackoff, Duration.ofMinutes(10));
        batchSize = bounded(batchSize, 4, 1, 100, "batch-size");
        extractionThreads = bounded(
                extractionThreads,
                2,
                1,
                16,
                "extraction-threads"
        );
        maxAttempts = bounded(maxAttempts, 5, 1, 20, "max-attempts");
        maxExtractedChars = bounded(
                maxExtractedChars,
                2_000_000,
                1_000,
                10_000_000,
                "max-extracted-chars"
        );
        maxDocxUncompressedBytes = maxDocxUncompressedBytes == null
                ? 104_857_600L
                : maxDocxUncompressedBytes;
        chunkSizeChars = bounded(
                chunkSizeChars,
                1_200,
                200,
                20_000,
                "chunk-size-chars"
        );
        chunkOverlapChars = chunkOverlapChars == null
                ? 150
                : chunkOverlapChars;

        if (pollDelay.isNegative() || pollDelay.isZero()) {
            throw invalid("poll-delay");
        }
        if (extractionTimeout.isNegative() || extractionTimeout.isZero()) {
            throw invalid("extraction-timeout");
        }
        if (processingLease.compareTo(extractionTimeout) <= 0) {
            throw new IllegalStateException(
                    "safeai.knowledge.ingestion.processing-lease должен "
                            + "быть больше extraction-timeout"
            );
        }
        if (initialBackoff.isNegative() || initialBackoff.isZero()
                || maxBackoff.compareTo(initialBackoff) < 0) {
            throw invalid("initial-backoff/max-backoff");
        }
        if (maxDocxUncompressedBytes < 1_048_576L
                || maxDocxUncompressedBytes > 1_073_741_824L) {
            throw invalid("max-docx-uncompressed-bytes");
        }
        if (chunkOverlapChars < 0
                || chunkOverlapChars >= chunkSizeChars) {
            throw invalid("chunk-overlap-chars");
        }
    }

    public Duration backoffForAttempt(int attempt) {
        int exponent = Math.clamp(attempt - 1, 0, 30);
        long multiplier = 1L << exponent;
        long millis;
        try {
            millis = Math.multiplyExact(
                    initialBackoff.toMillis(),
                    multiplier
            );
        } catch (ArithmeticException exception) {
            millis = Long.MAX_VALUE;
        }
        return Duration.ofMillis(
                Math.min(millis, maxBackoff.toMillis())
        );
    }

    private static Duration defaultDuration(
            Duration value,
            Duration defaultValue
    ) {
        return value == null ? defaultValue : value;
    }

    private static int bounded(
            Integer value,
            int defaultValue,
            int minimum,
            int maximum,
            String name
    ) {
        int resolved = value == null ? defaultValue : value;
        if (resolved < minimum || resolved > maximum) {
            throw invalid(name);
        }
        return resolved;
    }

    private static IllegalStateException invalid(String property) {
        return new IllegalStateException(
                "Некорректное значение safeai.knowledge.ingestion."
                        + property
        );
    }
}
