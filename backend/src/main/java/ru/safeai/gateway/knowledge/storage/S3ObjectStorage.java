package ru.safeai.gateway.knowledge.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
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