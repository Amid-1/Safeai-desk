package ru.safeai.gateway.knowledge.storage;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class S3ObjectStorageIntegrationTest {

    private static final String ACCESS_KEY =
            "safeai";

    private static final String SECRET_KEY =
            "safeai-local-change-me";

    private static final long MAX_UPLOAD_BYTES =
            26_214_400L;

    /*
     * Lifecycle контейнера управляется Testcontainers через @Container.
     * try-with-resources для поля здесь применять нельзя: контейнер должен
     * оставаться живым на протяжении выполнения всего test class.
     */
    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(
                    DockerImageName.parse(
                            "minio/minio:RELEASE.2025-07-23T15-54-02Z"
                    )
            )
                    .withEnv(
                            "MINIO_ROOT_USER",
                            ACCESS_KEY
                    )
                    .withEnv(
                            "MINIO_ROOT_PASSWORD",
                            SECRET_KEY
                    )
                    .withCommand(
                            "server",
                            "/data",
                            "--console-address",
                            ":9001"
                    )
                    .withExposedPorts(9000)
                    .waitingFor(
                            Wait.forHttp(
                                            "/minio/health/live"
                                    )
                                    .forPort(9000)
                                    .forStatusCode(200)
                    );

    @Test
    void existingBucket_putGetDelete_roundTripsObject()
            throws Exception {
        String bucket =
                bucketName();

        createBucket(bucket);

        S3ObjectStorage storage =
                new S3ObjectStorage(
                        properties(bucket)
                );

        byte[] payload =
                "SafeAI MinIO integration"
                        .getBytes(
                                StandardCharsets.UTF_8
                        );

        String key =
                "org/kb/document/version";

        storage.put(
                key,
                new ByteArrayInputStream(
                        payload
                )
        );

        StoredObject object =
                storage.get(key);

        assertThat(object.contentLength())
                .isEqualTo(payload.length);

        try (
                var input =
                        object.resource()
                                .getInputStream()
        ) {
            assertThat(
                    input.readAllBytes()
            ).containsExactly(payload);
        }

        storage.delete(key);

        assertThatThrownBy(
                () -> storage.get(key)
        ).isInstanceOf(
                NoSuchFileException.class
        );
    }

    @Test
    void missingBucket_failsFast() {
        String bucket =
                bucketName();

        assertThatThrownBy(
                () -> new S3ObjectStorage(
                        properties(bucket)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "не существует"
                );
    }

    @Test
    void missingObject_isMappedToNoSuchFileException()
            throws Exception {
        String bucket =
                bucketName();

        createBucket(bucket);

        S3ObjectStorage storage =
                new S3ObjectStorage(
                        properties(bucket)
                );

        assertThatThrownBy(
                () -> storage.get(
                        "missing/object"
                )
        ).isInstanceOf(
                NoSuchFileException.class
        );
    }

    @Test
    void blankStorageKeyIsRejectedBeforeNetworkRequest()
            throws Exception {
        String bucket =
                bucketName();

        createBucket(bucket);

        S3ObjectStorage storage =
                new S3ObjectStorage(
                        properties(bucket)
                );

        assertThatThrownBy(
                () -> storage.get(" ")
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "не должен быть пустым"
                );
    }

    private static void createBucket(
            String bucket
    ) throws Exception {
        try (
                MinioClient admin =
                        MinioClient.builder()
                                .endpoint(
                                        endpoint()
                                )
                                .credentials(
                                        ACCESS_KEY,
                                        SECRET_KEY
                                )
                                .build()
        ) {
            admin.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(bucket)
                            .build()
            );
        }
    }

    private static KnowledgeStorageProperties properties(
            String bucket
    ) {
        return new KnowledgeStorageProperties(
                KnowledgeStorageType.S3,
                null,
                MAX_UPLOAD_BYTES,
                endpoint(),
                ACCESS_KEY,
                SECRET_KEY,
                bucket
        );
    }

    /*
     * MinIO Testcontainer работает по HTTP внутри локального Docker test
     * environment. Это не production endpoint и не внешний HTTP-трафик.
     */
    @SuppressWarnings("HttpUrlsUsage")
    private static String endpoint() {
        return "http://"
                + MINIO.getHost()
                + ":"
                + MINIO.getMappedPort(9000);
    }

    private static String bucketName() {
        return "safeai-test-"
                + UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .substring(0, 20);
    }
}