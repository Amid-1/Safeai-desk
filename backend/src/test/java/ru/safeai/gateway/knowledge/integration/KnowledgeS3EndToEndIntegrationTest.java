package ru.safeai.gateway.knowledge.integration;

import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseService;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentService;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import ru.safeai.gateway.knowledge.storage.S3ObjectStorage;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class KnowledgeS3EndToEndIntegrationTest
        extends AbstractPostgresIntegrationTest {

    private static final String ACCESS_KEY =
            "safeai";

    private static final String SECRET_KEY =
            "safeai-local-change-me";

    private static final String BUCKET =
            "safeai-knowledge-e2e";

    /*
     * Lifecycle контейнера управляется Testcontainers через @Container.
     * Контейнер должен оставаться запущенным на протяжении всего test class.
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

    /**
     * Выполняется при подготовке Spring test context, до создания
     * S3ObjectStorage bean. Поэтому test bucket создаётся заранее,
     * а production S3ObjectStorage может оставаться fail-fast.
     */
    @DynamicPropertySource
    static void storageProperties(
            DynamicPropertyRegistry registry
    ) {
        ensureTestBucketExists();

        registry.add(
                "safeai.knowledge.storage.type",
                () -> "s3"
        );

        registry.add(
                "safeai.knowledge.storage.endpoint",
                KnowledgeS3EndToEndIntegrationTest::minioEndpoint
        );

        registry.add(
                "safeai.knowledge.storage.access-key",
                () -> ACCESS_KEY
        );

        registry.add(
                "safeai.knowledge.storage.secret-key",
                () -> SECRET_KEY
        );

        registry.add(
                "safeai.knowledge.storage.bucket",
                () -> BUCKET
        );
    }

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private ObjectStorage storage;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @MockitoBean
    private AuditEventService auditEventService;

    @Test
    void uploadMetadataAndMinioObject_areConsistentAndDownloadRoundTripsBytes()
            throws Exception {
        assertThat(storage)
                .isInstanceOf(
                        S3ObjectStorage.class
                );

        UUID organizationId =
                UUID.randomUUID();

        UUID adminId =
                UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Knowledge S3 E2E "
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8),
                true
        );

        insertUser(
                adminId,
                organizationId,
                "s3-e2e-"
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8)
                        + "@test.local",
                true,
                "ADMIN",
                Instant.now().minusSeconds(60)
        );

        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        adminId,
                        organizationId,
                        0L,
                        0L,
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_ADMIN"
                                )
                        )
                );

        var kb =
                knowledgeBaseService.create(
                        new CreateKnowledgeBaseRequest(
                                "S3 E2E",
                                null,
                                KnowledgeBaseVisibility.ORGANIZATION
                        ),
                        principal
                );

        byte[] payload =
                "VECTOR-2026-19\nSafeAI S3 end-to-end"
                        .getBytes(
                                StandardCharsets.UTF_8
                        );

        var uploaded =
                knowledgeDocumentService.uploadNew(
                        kb.id(),
                        "Architecture",
                        new MockMultipartFile(
                                "file",
                                "architecture.txt",
                                "application/octet-stream",
                                payload
                        ),
                        principal
                );

        String storageKey =
                jdbcTemplate.queryForObject(
                        """
                        select storage_key
                        from public.knowledge_document_versions
                        where id = ?
                        """,
                        String.class,
                        uploaded.currentVersionId()
                );

        assertThat(storageKey)
                .isNotBlank();

        var stored =
                storage.get(storageKey);

        assertThat(stored.contentLength())
                .isEqualTo(
                        payload.length
                );

        try (
                var input =
                        stored.resource()
                                .getInputStream()
        ) {
            assertThat(
                    input.readAllBytes()
            ).containsExactly(
                    payload
            );
        }

        var downloaded =
                knowledgeDocumentService.download(
                        kb.id(),
                        uploaded.id(),
                        null,
                        principal
                );

        try (
                var input =
                        downloaded.object()
                                .resource()
                                .getInputStream()
        ) {
            assertThat(
                    input.readAllBytes()
            ).containsExactly(
                    payload
            );
        }

        assertThat(downloaded.filename())
                .isEqualTo(
                        "architecture.txt"
                );

        assertThat(downloaded.mediaType())
                .isEqualTo(
                        "text/plain"
                );

        String sha256 =
                jdbcTemplate.queryForObject(
                        """
                        select sha256
                        from public.knowledge_document_versions
                        where id = ?
                        """,
                        String.class,
                        uploaded.currentVersionId()
                );

        assertThat(sha256)
                .hasSize(64);
    }

    @Test
    void transactionRollback_removesAlreadyUploadedMinioObject()
            throws Exception {
        long beforeObjects =
                objectCount();

        UUID organizationId =
                UUID.randomUUID();

        UUID adminId =
                UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Knowledge S3 Rollback "
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8),
                true
        );

        insertUser(
                adminId,
                organizationId,
                "s3-rollback-"
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8)
                        + "@test.local",
                true,
                "ADMIN",
                Instant.now().minusSeconds(60)
        );

        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        adminId,
                        organizationId,
                        0L,
                        0L,
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_ADMIN"
                                )
                        )
                );

        var kb =
                knowledgeBaseService.create(
                        new CreateKnowledgeBaseRequest(
                                "Rollback KB",
                                null,
                                KnowledgeBaseVisibility.ORGANIZATION
                        ),
                        principal
                );

        doThrow(
                new IllegalStateException(
                        "audit write failed"
                )
        )
                .when(auditEventService)
                .record(
                        any(SafeAiUserPrincipal.class),
                        eq(organizationId),
                        eq(
                                AuditEventType
                                        .KNOWLEDGE_DOCUMENT_VERSION_UPLOADED
                        ),
                        anyMap()
                );

        assertThatThrownBy(
                () -> knowledgeDocumentService.uploadNew(
                        kb.id(),
                        "Rollback object",
                        new MockMultipartFile(
                                "file",
                                "rollback.txt",
                                "text/plain",
                                "rollback payload"
                                        .getBytes(
                                                StandardCharsets.UTF_8
                                        )
                        ),
                        principal
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "audit write failed"
                );

        Integer documentCount =
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from public.knowledge_documents
                        where knowledge_base_id = ?
                          and name = 'Rollback object'
                        """,
                        Integer.class,
                        kb.id()
                );

        assertThat(documentCount)
                .isZero();

        assertThat(objectCount())
                .isEqualTo(
                        beforeObjects
                );
    }

    /**
     * Создаёт test bucket до инициализации S3ObjectStorage.
     *
     * <p>Метод идемпотентный: если bucket уже существует,
     * повторно он не создаётся.</p>
     */
    private static void ensureTestBucketExists() {
        try (
                MinioClient client =
                        MinioClient.builder()
                                .endpoint(
                                        minioEndpoint()
                                )
                                .credentials(
                                        ACCESS_KEY,
                                        SECRET_KEY
                                )
                                .build()
        ) {
            boolean exists =
                    client.bucketExists(
                            BucketExistsArgs.builder()
                                    .bucket(BUCKET)
                                    .build()
                    );

            if (!exists) {
                client.makeBucket(
                        MakeBucketArgs.builder()
                                .bucket(BUCKET)
                                .build()
                );
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Не удалось подготовить MinIO bucket '"
                            + BUCKET
                            + "' для S3 E2E test",
                    exception
            );
        }
    }

    /**
     * Считает реальные объекты в MinIO.
     */
    private static long objectCount()
            throws Exception {
        try (
                MinioClient client =
                        MinioClient.builder()
                                .endpoint(
                                        minioEndpoint()
                                )
                                .credentials(
                                        ACCESS_KEY,
                                        SECRET_KEY
                                )
                                .build()
        ) {
            long count =
                    0L;

            for (
                    var result :
                    client.listObjects(
                            ListObjectsArgs.builder()
                                    .bucket(BUCKET)
                                    .recursive(true)
                                    .build()
                    )
            ) {
                result.get();
                count++;
            }

            return count;
        }
    }

    /**
     * MinIO Testcontainer в integration test работает по локальному HTTP.
     * Production S3 endpoint должен использовать HTTPS.
     */
    @SuppressWarnings("HttpUrlsUsage")
    private static String minioEndpoint() {
        return "http://"
                + MINIO.getHost()
                + ":"
                + MINIO.getMappedPort(9000);
    }
}