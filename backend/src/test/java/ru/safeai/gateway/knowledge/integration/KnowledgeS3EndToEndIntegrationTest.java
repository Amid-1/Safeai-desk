package ru.safeai.gateway.knowledge.integration;

import io.minio.BucketExistsArgs;
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
import ru.safeai.gateway.knowledge.storage.reconciliation.KnowledgeStorageReconciliationScheduler;
import ru.safeai.gateway.knowledge.storage.reconciliation.KnowledgeStorageUploadJournal;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

        // Never run background S3 cleanup in this integration-test context.
        // The test exercises reconciliation explicitly with a controlled clock.
        registry.add(
                "safeai.knowledge.storage-reconciliation.enabled",
                () -> "false"
        );
    }

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private KnowledgeStorageUploadJournal uploadJournal;

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

        String journalState = jdbcTemplate.queryForObject(
                """
                select state
                from public.knowledge_storage_upload_intents
                where document_version_id = ?
                """,
                String.class,
                uploaded.currentVersionId()
        );
        assertThat(journalState).isEqualTo("LINKED");
    }

    @Test
    void transactionRollback_keepsConfirmedS3ObjectUntilFencedReconciliation()
            throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Knowledge S3 Rollback "
                        + UUID.randomUUID().toString().substring(0, 8),
                true
        );

        insertUser(
                adminId,
                organizationId,
                "s3-rollback-"
                        + UUID.randomUUID().toString().substring(0, 8)
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
                        Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                );

        var kb = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest(
                        "Rollback KB",
                        null,
                        KnowledgeBaseVisibility.ORGANIZATION
                ),
                principal
        );

        // Force the *metadata transaction* to fail only after a successful PUT
        // and the separately committed durable journal state STORED.
        doThrow(new IllegalStateException("audit write failed"))
                .when(auditEventService)
                .record(
                        any(SafeAiUserPrincipal.class),
                        eq(organizationId),
                        eq(AuditEventType.KNOWLEDGE_DOCUMENT_VERSION_UPLOADED),
                        anyMap()
                );

        byte[] payload = "rollback payload".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> knowledgeDocumentService.uploadNew(
                kb.id(),
                "Rollback object",
                new MockMultipartFile(
                        "file",
                        "rollback.txt",
                        "text/plain",
                        payload
                ),
                principal
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit write failed");

        Integer documentCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from public.knowledge_documents
                where knowledge_base_id = ?
                  and name = 'Rollback object'
                """,
                Integer.class,
                kb.id()
        );
        assertThat(documentCount).isZero();

        UploadIntent intent = jdbcTemplate.queryForObject(
                """
                select id, document_version_id, storage_key, state
                from public.knowledge_storage_upload_intents
                where organization_id = ? and knowledge_base_id = ?
                """,
                (rs, rowNum) -> new UploadIntent(
                        rs.getObject("id", UUID.class),
                        rs.getObject("document_version_id", UUID.class),
                        rs.getString("storage_key"),
                        rs.getString("state")
                ),
                organizationId,
                kb.id()
        );
        assertThat(intent).isNotNull();
        assertThat(intent.state()).isEqualTo("STORED");

        Integer versionCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from public.knowledge_document_versions
                where id = ?
                """,
                Integer.class,
                intent.versionId()
        );
        assertThat(versionCount).isZero();

        // V56 intentionally keeps this confirmed object, rather than
        // immediately deleting it on a failed/possibly ambiguous DB commit.
        try (var input = storage.get(intent.storageKey())
                .resource().getInputStream()) {
            assertThat(input.readAllBytes()).containsExactly(payload);
        }

        // The scheduler is disabled in the Spring context. Invoke it with a
        // controlled clock to verify that a fresh STORED object is protected.
        new KnowledgeStorageReconciliationScheduler(
                uploadJournal,
                storage,
                Clock.fixed(Instant.now(), ZoneOffset.UTC)
        ).poll();

        assertThat(journalState(intent.id())).isEqualTo("STORED");
        try (var input = storage.get(intent.storageKey())
                .resource().getInputStream()) {
            assertThat(input.readAllBytes()).containsExactly(payload);
        }

        // After the 24h grace period, the *same fenced reconciliation path*
        // removes the unlinked S3 object and records durable CLEANED state.
        new KnowledgeStorageReconciliationScheduler(
                uploadJournal,
                storage,
                Clock.fixed(Instant.now().plus(Duration.ofHours(25)), ZoneOffset.UTC)
        ).poll();

        assertThat(journalState(intent.id())).isEqualTo("CLEANED");
        assertThatThrownBy(() -> storage.get(intent.storageKey()))
                .isInstanceOf(NoSuchFileException.class);
    }

    private String journalState(UUID intentId) {
        return jdbcTemplate.queryForObject(
                """
                select state
                from public.knowledge_storage_upload_intents
                where id = ?
                """,
                String.class,
                intentId
        );
    }

    private record UploadIntent(
            UUID id,
            UUID versionId,
            String storageKey,
            String state
    ) { }

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
