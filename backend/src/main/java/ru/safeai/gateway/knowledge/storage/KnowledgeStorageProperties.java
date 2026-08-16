package ru.safeai.gateway.knowledge.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Конфигурация объектного хранилища базы знаний.
 *
 * <p>Секреты не должны храниться в application.yml репозитория.
 * Для production access-key/secret-key должны приходить из environment
 * variables или внешнего secret storage.</p>
 */
@ConfigurationProperties(
        prefix = "safeai.knowledge.storage"
)
public record KnowledgeStorageProperties(
        KnowledgeStorageType type,
        Path localRoot,
        long maxUploadBytes,
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
            26_214_400L;

    private static final String DEFAULT_BUCKET =
            "safeai-knowledge";

    public KnowledgeStorageProperties {
        type = type == null
                ? KnowledgeStorageType.LOCAL
                : type;

        localRoot = localRoot == null
                ? DEFAULT_LOCAL_ROOT
                : localRoot;

        maxUploadBytes = maxUploadBytes <= 0
                ? DEFAULT_MAX_UPLOAD_BYTES
                : maxUploadBytes;

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
            bucket = DEFAULT_BUCKET;
        }

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
        }
    }

    private static String normalizeNullable(
            String value
    ) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
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
}