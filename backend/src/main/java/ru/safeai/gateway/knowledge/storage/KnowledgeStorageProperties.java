package ru.safeai.gateway.knowledge.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Object-storage configuration for Knowledge documents.
 *
 * <p>Secrets must come from environment variables or an external secret
 * manager. Do not keep real credentials in application*.yml committed to Git.</p>
 */
@ConfigurationProperties(
        prefix = "safeai.knowledge.storage"
)
public record KnowledgeStorageProperties(
        KnowledgeStorageType type,
        Path localRoot,
        Long maxUploadBytes,
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket
) {

    private static final Path DEFAULT_LOCAL_ROOT =
            Path.of(
                    "./var/knowledge-objects"
            );

    private static final long DEFAULT_MAX_UPLOAD_BYTES =
            25L * 1024L * 1024L;

    private static final long MIN_MAX_UPLOAD_BYTES =
            1024L * 1024L;

    private static final long MAX_MAX_UPLOAD_BYTES =
            100L * 1024L * 1024L;

    private static final String DEFAULT_BUCKET =
            "safeai-knowledge";

    private static final Pattern BUCKET_NAME =
            Pattern.compile(
                    "[a-z0-9][a-z0-9.-]*[a-z0-9]"
            );

    public KnowledgeStorageProperties {
        type =
                type == null
                        ? KnowledgeStorageType.LOCAL
                        : type;

        localRoot =
                localRoot == null
                        ? DEFAULT_LOCAL_ROOT
                        : localRoot.normalize();

        maxUploadBytes =
                resolveMaxUploadBytes(
                        maxUploadBytes
                );

        endpoint =
                normalizeNullable(
                        endpoint
                );

        accessKey =
                normalizeNullable(
                        accessKey
                );

        secretKey =
                normalizeNullable(
                        secretKey
                );

        bucket =
                normalizeNullable(
                        bucket
                );

        if (bucket == null) {
            bucket =
                    DEFAULT_BUCKET;
        }

        validateBucket(
                bucket
        );

        if (type == KnowledgeStorageType.S3) {
            requireConfigured(
                    endpoint,
                    "endpoint"
            );

            requireConfigured(
                    accessKey,
                    "access-key"
            );

            requireConfigured(
                    secretKey,
                    "secret-key"
            );

            validateEndpoint(
                    endpoint
            );
        }
    }

    private static long resolveMaxUploadBytes(
            Long value
    ) {
        if (value == null) {
            return DEFAULT_MAX_UPLOAD_BYTES;
        }

        if (value < MIN_MAX_UPLOAD_BYTES
                || value > MAX_MAX_UPLOAD_BYTES) {
            throw invalid(
                    "max-upload-bytes"
            );
        }

        return value;
    }

    private static String normalizeNullable(
            String value
    ) {
        return value == null
                || value.isBlank()
                ? null
                : value.strip();
    }

    private static void requireConfigured(
            String value,
            String property
    ) {
        if (value == null) {
            throw new IllegalStateException(
                    "safeai.knowledge.storage."
                            + property
                            + " должен быть задан "
                            + "для storage.type=s3"
            );
        }
    }

    private static void validateEndpoint(
            String value
    ) {
        final URI uri;

        try {
            uri =
                    URI.create(
                            value
                    );
        } catch (IllegalArgumentException exception) {
            throw invalidEndpoint(
                    exception
            );
        }

        String scheme =
                uri.getScheme() == null
                        ? ""
                        : uri.getScheme()
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (!scheme.equals(
                "http"
        )
                && !scheme.equals(
                "https"
        )) {
            throw invalid(
                    "endpoint"
            );
        }

        if (uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null) {
            throw invalid(
                    "endpoint"
            );
        }
    }

    private static void validateBucket(
            String value
    ) {
        if (value.length() < 3
                || value.length() > 63
                || !BUCKET_NAME.matcher(
                value
        ).matches()
                || value.contains(
                ".."
        )) {
            throw invalid(
                    "bucket"
            );
        }
    }

    private static IllegalStateException invalid(
            String property
    ) {
        return new IllegalStateException(
                "Некорректное значение safeai.knowledge.storage."
                        + property
        );
    }

    private static IllegalStateException invalidEndpoint(
            Throwable cause
    ) {
        return new IllegalStateException(
                "Некорректное значение safeai.knowledge.storage.endpoint",
                cause
        );
    }
}