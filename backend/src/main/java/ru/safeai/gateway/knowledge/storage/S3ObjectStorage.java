package ru.safeai.gateway.knowledge.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectRetentionArgs;
import io.minio.IsObjectLegalHoldEnabledArgs;
import io.minio.ListObjectsArgs;
import io.minio.Result;
import io.minio.messages.Item;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

@Component
@ConditionalOnProperty(
        prefix = "safeai.knowledge.storage",
        name = "type",
        havingValue = "s3"
)
public class S3ObjectStorage implements ObjectStorage {

    private static final Logger log =
            LoggerFactory.getLogger(
                    S3ObjectStorage.class
            );

    private static final long MULTIPART_PART_SIZE =
            10L * 1024L * 1024L;

    // These codes are only candidates. A version-specific metadata check is
    // mandatory before an S3 refusal may be classified as Object Lock.
    private static final Set<String> POSSIBLE_OBJECT_LOCK_DENIAL_CODES =
            Set.of("InvalidRequest", "AccessDenied", "ObjectLocked", "InvalidObjectState");

    private static final Set<String> OBJECT_NOT_FOUND_CODES =
            Set.of(
                    "NoSuchKey",
                    "NoSuchObject",
                    "NotFound"
            );

    private final MinioClient client;
    private final String bucket;

    public S3ObjectStorage(
            KnowledgeStorageProperties properties
    ) {
        Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );

        this.bucket =
                Objects.requireNonNull(
                        properties.bucket(),
                        "bucket не должен быть null"
                );

        this.client =
                MinioClient.builder()
                        .endpoint(
                                properties.endpoint()
                        )
                        .credentials(
                                properties.accessKey(),
                                properties.secretKey()
                        )
                        .build();

        ensureBucketExists();
    }

    @Override
    public void put(
            String key,
            InputStream content
    ) throws IOException {
        requireKey(key);

        Objects.requireNonNull(
                content,
                "content не должен быть null"
        );

        try {
            client.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .stream(
                                    content,
                                    -1,
                                    MULTIPART_PART_SIZE
                            )
                            .contentType(
                                    "application/octet-stream"
                            )
                            .build()
            );
        } catch (Exception exception) {
            throw new IOException(
                    "Не удалось сохранить объект в S3 storage",
                    exception
            );
        }
    }

    @Override
    public StoredObject get(
            String key
    ) throws IOException {
        requireKey(key);

        try {
            StatObjectResponse stat =
                    client.statObject(
                            StatObjectArgs.builder()
                                    .bucket(bucket)
                                    .object(key)
                                    .build()
                    );

            InputStream stream =
                    client.getObject(
                            GetObjectArgs.builder()
                                    .bucket(bucket)
                                    .object(key)
                                    .build()
                    );

            return new StoredObject(
                    new InputStreamResource(
                            stream
                    ),
                    stat.size()
            );
        } catch (ErrorResponseException exception) {
            if (isObjectNotFound(exception)) {
                throw new NoSuchFileException(
                        key
                );
            }

            throw new IOException(
                    "Не удалось получить объект из S3 storage",
                    exception
            );
        } catch (Exception exception) {
            throw new IOException(
                    "Не удалось получить объект из S3 storage",
                    exception
            );
        }
    }

    @Override
    public void delete(
            String key
    ) throws IOException {
        requireKey(key);

        try {
            client.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .build()
            );
        } catch (Exception exception) {
            throw new IOException(
                    "Не удалось удалить объект из S3 storage",
                    exception
            );
        }
    }

    /**
     * Removes every enumerated version and delete marker; never bypasses Object Lock.
     * A storage failure or ambiguous result is NOT success. Caller must keep the
     * durable deletion operation pending for reconciliation.
     *
     * Only call when document publication and late uploads are fenced elsewhere.
     */
    @Override
    public void purgeAllVersionsVerified(String key) throws IOException {
        purgeAllVersionsVerified(key, () -> { });
    }

    /**
     * Removes all versions and delete markers in bounded batches. The listing
     * is restarted after each batch, so deleting a page cannot invalidate a
     * provider continuation marker. JVM memory is bounded independently of the
     * number of versions for one key. A final fresh listing plus HEAD verifies
     * absence; ambiguous network outcomes are retried by the durable executor.
     */
    @Override
    public void purgeAllVersionsVerified(String key, Runnable heartbeat) throws IOException {
        requireKey(key);
        Objects.requireNonNull(heartbeat, "heartbeat");
        final int batchSize = 128;
        try {
            int iterations = 0;
            while (true) {
                if (++iterations > 10000) {
                    throw new IOException("S3 erasure exceeded safe iteration limit");
                }
                heartbeat.run();
                java.util.List<Item> batch = new java.util.ArrayList<>(batchSize);
                Iterable<Result<Item>> results = client.listObjects(
                        ListObjectsArgs.builder().bucket(bucket).prefix(key)
                                .recursive(true).includeVersions(true).build());
                for (Result<Item> result : results) {
                    heartbeat.run();
                    Item item = result.get(); // Lazy provider/network errors must propagate.
                    if (key.equals(item.objectName())) {
                        batch.add(item);
                        if (batch.size() >= batchSize) break;
                    }
                }

                if (batch.isEmpty()) {
                    // The version listing is authoritative for historical versions;
                    // HEAD independently detects an unversioned current object.
                    if (!objectExists(key)) return;
                    client.removeObject(RemoveObjectArgs.builder()
                            .bucket(bucket).object(key).build());
                    continue;
                }

                for (Item item : batch) {
                    heartbeat.run();
                    RemoveObjectArgs.Builder builder = RemoveObjectArgs.builder()
                            .bucket(bucket).object(key);
                    String versionId = item.versionId();
                    if (versionId != null && !versionId.isBlank()) {
                        builder.versionId(versionId);
                    }
                    // Never bypass Governance/Compliance retention or legal holds.
                    try {
                        client.removeObject(builder.build());
                    } catch (ErrorResponseException refused) {
                        if (versionId != null && !versionId.isBlank()
                                && isPossibleObjectLockRefusal(refused)
                                && isVersionProtectedByObjectLock(key, versionId, refused)) {
                            throw new VerifiedObjectLockException(
                                    "S3 Object Lock verified for the refused object version", refused);
                        }
                        throw refused;
                    }
                }
            }
        } catch (VerifiedObjectLockException exception) {
            throw exception;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("S3 physical erasure cannot be verified", exception);
        }
    }

    private static boolean isPossibleObjectLockRefusal(ErrorResponseException refused) {
        return refused.errorResponse() != null
                && POSSIBLE_OBJECT_LOCK_DENIAL_CODES.contains(refused.errorResponse().code());
    }

    /**
     * Classifies ONLY a failed version-specific DELETE. Confirm protection on
     * that exact version; a generic S3 error must never imply legal retention.
     * If metadata reads are denied or fail, classification fails closed:
     * the caller retains its durable operation for retry/review, never DELETED.
     */
    private boolean isVersionProtectedByObjectLock(
            String key, String versionId, ErrorResponseException deleteError) {
        try {
            Retention retention = client.getObjectRetention(
                    GetObjectRetentionArgs.builder()
                            .bucket(bucket).object(key).versionId(versionId).build());
            if (retention != null
                    && (retention.mode() == RetentionMode.COMPLIANCE
                            || retention.mode() == RetentionMode.GOVERNANCE)
                    && retention.retainUntilDate() != null
                    && retention.retainUntilDate().toInstant().isAfter(Instant.now())) {
                return true;
            }
        } catch (Exception metadataError) {
            // Some providers return an error when no retention is configured.
            // Legal hold is independent, so query it even if this read fails.
            deleteError.addSuppressed(metadataError);
        }
        try {
            return Boolean.TRUE.equals(client.isObjectLegalHoldEnabled(
                    IsObjectLegalHoldEnabledArgs.builder()
                            .bucket(bucket).object(key).versionId(versionId).build()));
        } catch (Exception metadataError) {
            // Inability to verify is not evidence of a hold or of its absence.
            deleteError.addSuppressed(metadataError);
            return false;
        }
    }

    private boolean objectExists(String key) throws Exception {
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException exception) {
            if (isObjectNotFound(exception)) return false;
            throw exception;
        }
    }

    private void ensureBucketExists() {
        try {
            boolean exists =
                    client.bucketExists(
                            BucketExistsArgs.builder()
                                    .bucket(bucket)
                                    .build()
                    );

            if (!exists) {
                throw new IllegalStateException(
                        "S3 bucket '"
                                + bucket
                                + "' не существует. "
                                + "Bucket должен быть создан инфраструктурой "
                                + "до запуска приложения."
                );
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Не удалось проверить S3 bucket '"
                            + bucket
                            + "' при старте приложения",
                    exception
            );
        }
    }

    private static boolean isObjectNotFound(
            ErrorResponseException exception
    ) {
        return exception.errorResponse() != null
                && OBJECT_NOT_FOUND_CODES.contains(
                        exception.errorResponse()
                                .code()
                );
    }

    private static void requireKey(
            String key
    ) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "Storage key не должен быть пустым"
            );
        }
    }

    @PreDestroy
    void closeClient() {
        try {
            client.close();
        } catch (Exception exception) {
            log.warn(
                    "Не удалось корректно закрыть MinIO client",
                    exception
            );
        }
    }
}