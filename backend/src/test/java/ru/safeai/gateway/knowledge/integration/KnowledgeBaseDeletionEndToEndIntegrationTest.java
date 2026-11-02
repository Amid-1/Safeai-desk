package ru.safeai.gateway.knowledge.integration;

import io.minio.GetBucketVersioningArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.SetBucketVersioningArgs;
import io.minio.messages.Item;
import io.minio.messages.VersioningConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.knowledge.testsupport.KnowledgeErasureTestJournal;
import ru.safeai.gateway.common.exception.ConflictException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseDeletionResponse;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseDeletionExecutor;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseDeletionService;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionExecutor;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageProperties;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageType;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import ru.safeai.gateway.knowledge.storage.S3ObjectStorage;
import ru.safeai.gateway.knowledge.storage.StoredObject;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Executes the real V82 -> V78/V81 -> versioned S3 -> V82 completion path.
 * All deletion flags remain OFF in the Spring context. Only manually created
 * workers and a transaction-scoped service operate on random test IDs.
 * PostgreSQL is provided by AbstractPostgresIntegrationTest/Testcontainers;
 * the S3 endpoint below is a separate, disposable Testcontainers instance.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
class KnowledgeBaseDeletionEndToEndIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ACCESS_KEY = "safeai";
    private static final String SECRET_KEY = "safeai-local-change-me";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("pgsty/silo:RELEASE.2026-10-18T00-00-00Z"))
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data", "--console-address", ":9001")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live")
                    .forPort(9000).forStatusCode(200));

    @Autowired private AuditEventService audit;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void physicallyErasesMultipleDocumentsAndAllVersionsWithoutTouchingNeighbor() throws Exception {
        UUID adminId = createAdmin();
        UUID base = createBase(adminId);
        UUID first = createDocument(adminId, base);
        UUID second = createDocument(adminId, base);
        String prefix = "safeai-e2e/" + UUID.randomUUID() + "/";
        String firstKey = prefix + "first-v1";
        String secondKey = prefix + "first-v2";
        String thirdKey = prefix + "second-v1";
        String neighbor = prefix + "neighbor";
        createVersion(adminId, base, first, 1, firstKey);
        createVersion(adminId, base, first, 2, secondKey);
        createVersion(adminId, base, second, 1, thirdKey);

        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        String bucket = "safeai-base-e2e-" + UUID.randomUUID().toString().replace("-", "");
        try (MinioClient client = MinioClient.builder().endpoint(endpoint)
                .credentials(ACCESS_KEY, SECRET_KEY).build()) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            client.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(bucket)
                    .config(new VersioningConfiguration(
                            VersioningConfiguration.Status.ENABLED, false)).build());
            assertThat(client.getBucketVersioning(GetBucketVersioningArgs.builder()
                    .bucket(bucket).build()).status())
                    .isEqualTo(VersioningConfiguration.Status.ENABLED);

            S3ObjectStorage storage = new S3ObjectStorage(new KnowledgeStorageProperties(
                    KnowledgeStorageType.S3, null, 26_214_400L,
                    endpoint, ACCESS_KEY, SECRET_KEY, bucket));
            try {
                for (String key : List.of(firstKey, secondKey, thirdKey)) {
                    storage.put(key, bytes("original-1"));
                    storage.put(key, bytes("original-2"));
                }
                storage.delete(secondKey); // A delete marker must also be purged.
                storage.put(neighbor, bytes("keep-original"));
                storage.put(neighbor, bytes("keep-latest"));
                List<String> neighborVersionIds = versionIds(client, bucket, neighbor);
                assertThat(neighborVersionIds).hasSize(2);
                assertThat(exactVersions(client, bucket, firstKey)).hasSize(2);
                assertThat(exactVersions(client, bucket, secondKey)).hasSize(3);
                assertThat(exactVersions(client, bucket, thirdKey)).hasSize(2);

                SafeAiUserPrincipal admin = principal(adminId);
                String idempotencyKey = "base-e2e-" + UUID.randomUUID();
                KnowledgeBaseDeletionResponse accepted = accept(base, idempotencyKey, admin);
                assertThat(accepted.state()).isEqualTo("DELETING");
                KnowledgeBaseDeletionResponse replay = new TransactionTemplate(transactionManager)
                        .execute(tx -> deletionService().accept(base, 0L, idempotencyKey, admin));
                assertThat(replay).isEqualTo(accepted);
                assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                        .execute(tx -> deletionService().accept(base, 0L,
                                "different-key-" + UUID.randomUUID(), admin)))
                        .isInstanceOf(ConflictException.class);
                assertThat(count("SELECT count(*) FROM knowledge_document_deletions_v78 "
                        + "WHERE knowledge_base_id=? AND state='DELETING'", base)).isEqualTo(2);
                assertThat(count("SELECT count(*) FROM knowledge_documents "
                        + "WHERE knowledge_base_id=? AND NOT enabled AND lifecycle_state='DELETING'",
                        base)).isEqualTo(2);

                // Parent must not delete the base before both children finish.
                parentWorker().poll();
                assertThat(baseDeletionState(accepted.operationId()))
                        .isEqualTo("DELETING");
                assertThat(count("SELECT count(*) FROM knowledge_bases WHERE id=?", base))
                        .isEqualTo(1);

                childWorker(storage).poll();
                assertThat(count("SELECT count(*) FROM knowledge_document_deletions_v78 "
                        + "WHERE knowledge_base_id=? AND state='DELETED'", base)).isEqualTo(2);
                for (String key : List.of(firstKey, secondKey, thirdKey)) {
                    assertThat(exactVersions(client, bucket, key)).isEmpty();
                }
                assertThat(versionIds(client, bucket, neighbor))
                        .containsExactlyInAnyOrderElementsOf(neighborVersionIds);

                // The earlier parent poll installed a 20-second retry window;
                // move ONLY this disposable test operation forward.
                jdbcTemplate.update("UPDATE knowledge_base_deletions_v82 "
                        + "SET next_attempt_at=clock_timestamp() WHERE id=?",
                        accepted.operationId());
                parentWorker().poll();
                assertThat(baseDeletionState(accepted.operationId()))
                        .isEqualTo("DELETED");
                assertThat(count("SELECT count(*) FROM knowledge_bases WHERE id=?", base))
                        .isZero();
                assertThat(count("SELECT count(*) FROM knowledge_documents "
                        + "WHERE knowledge_base_id=?", base)).isZero();
                assertThat(count("SELECT count(*) FROM knowledge_document_versions "
                        + "WHERE knowledge_base_id=?", base)).isZero();
                assertThat(count("SELECT count(*) FROM knowledge_document_chunks "
                        + "WHERE knowledge_base_id=?", base)).isZero();
                assertThat(count("SELECT count(*) FROM knowledge_document_erasure_objects_v81 i "
                        + "JOIN knowledge_document_deletions_v78 d ON d.id=i.operation_id "
                        + "WHERE d.knowledge_base_id=?", base)).isZero();
                assertThat(count("SELECT count(*) FROM knowledge_base_identities_v82 "
                        + "WHERE id=?", base)).isEqualTo(1);
                assertThat(count("SELECT count(*) FROM knowledge_base_deletions_v82 "
                        + "WHERE knowledge_base_id=? AND state='DELETED'", base)).isEqualTo(1);
                assertThat(count("SELECT count(*) FROM audit_outbox "
                        + "WHERE event_type='KNOWLEDGE_BASE_DELETION_REQUESTED' "
                        + "AND organization_id=?", PLATFORM_ORGANIZATION_ID)).isEqualTo(1);
            } finally {
                // Test-only resource cleanup: production closeClient() is package-private.
                ReflectionTestUtils.invokeMethod(storage, "closeClient");
            }
        }
    }

    @Test
    void emptyBaseCompletesWithoutAffectingAnotherBase() {
        UUID actor = createAdmin();
        UUID target = createBase(actor);
        UUID neighbor = createBase(actor);
        KnowledgeBaseDeletionResponse accepted = accept(target,
                "base-empty-" + UUID.randomUUID(), principal(actor));

        parentWorker().poll();

        assertThat(baseDeletionState(accepted.operationId()))
                .isEqualTo("DELETED");
        assertThat(count("SELECT count(*) FROM knowledge_bases WHERE id=?", target)).isZero();
        assertThat(count("SELECT count(*) FROM knowledge_bases "
                + "WHERE id=? AND lifecycle_state='AVAILABLE' AND enabled", neighbor))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM knowledge_base_identities_v82 WHERE id=?", target))
                .isEqualTo(1);
    }

    @Test
    void ambiguousUploadQuarantinesDocumentAndParentWithoutStorageCalls() {
        UUID actor = createAdmin();
        UUID base = createBase(actor);
        UUID document = createDocument(actor, base);
        jdbcTemplate.update("""
                INSERT INTO knowledge_storage_upload_intents(
                    id, organization_id, knowledge_base_id, document_id,
                    document_version_id, created_by_user_id, storage_key,
                    content_sha256, content_size_bytes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
                """, UUID.randomUUID(), PLATFORM_ORGANIZATION_ID, base, document,
                UUID.randomUUID(), actor, "uncertain-base-e2e/" + UUID.randomUUID(),
                "b".repeat(64));
        KnowledgeBaseDeletionResponse accepted = accept(base,
                "base-uncertain-" + UUID.randomUUID(), principal(actor));
        ObjectStorage storage = org.mockito.Mockito.mock(ObjectStorage.class);

        childWorker(storage).poll();
        assertThat(count("SELECT count(*) FROM knowledge_document_deletions_v78 "
                + "WHERE knowledge_base_id=? AND state='NEEDS_REVIEW'", base)).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(storage);
        parentWorker().poll();

        assertThat(baseDeletionState(accepted.operationId()))
                .isEqualTo("NEEDS_REVIEW");
        assertThat(count("SELECT count(*) FROM knowledge_bases WHERE id=? "
                + "AND lifecycle_state='DELETING' AND NOT enabled", base)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM knowledge_documents WHERE id=? "
                + "AND lifecycle_state='DELETING' AND NOT enabled", document)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM knowledge_storage_upload_intents "
                + "WHERE document_id=? AND state='INTENT'", document)).isEqualTo(1);
    }

    @Test
    void retentionBlockedDocumentPreventsParentFinalization() {
        UUID actor = createAdmin();
        UUID base = createBase(actor);
        UUID doc = createDocument(actor, base);
        KnowledgeBaseDeletionResponse accepted = accept(base,
                "base-retention-" + UUID.randomUUID(), principal(actor));

        // Simulate the already-classified terminal result of an independent
        // Object Lock test. This verifies propagation, NOT S3 Object Lock itself.
        jdbcTemplate.update("""
                UPDATE knowledge_document_deletions_v78
                   SET state='BLOCKED_BY_RETENTION',
                       last_error_code='OBJECT_LOCK'
                 WHERE knowledge_base_id=? AND document_id=? AND state='DELETING'
                """, base, doc);
        parentWorker().poll();

        assertThat(baseDeletionState(accepted.operationId()))
                .isEqualTo("BLOCKED_BY_RETENTION");
        assertThat(count("SELECT count(*) FROM knowledge_bases "
                + "WHERE id=? AND lifecycle_state='DELETING' AND NOT enabled", base))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM knowledge_documents "
                + "WHERE id=? AND lifecycle_state='DELETING' AND NOT enabled", doc))
                .isEqualTo(1);
    }

    @Test
    void failedStoragePurgeDoesNotCompleteAndMayBeSafelyRetried() {
        UUID actor = createAdmin();
        UUID base = createBase(actor);
        UUID document = createDocument(actor, base);
        String key = "retry-base-e2e/" + UUID.randomUUID();
        createVersion(actor, base, document, 1, key);
        KnowledgeBaseDeletionResponse accepted = accept(base,
                "base-retry-" + UUID.randomUUID(), principal(actor));
        AtomicInteger purgeCalls = new AtomicInteger();
        ObjectStorage storage = new ObjectStorage() {
            @Override public void put(String k, InputStream content) { }
            @Override public StoredObject get(String k) { return null; }
            @Override public void delete(String k) { }
            @Override public void purgeAllVersionsVerified(String k) throws IOException {
                assertThat(k).isEqualTo(key);
                if (purgeCalls.getAndIncrement() == 0) {
                    throw new IOException("simulated transient storage failure");
                }
            }
        };

        childWorker(storage).poll();
        assertThat(purgeCalls.get()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM knowledge_document_deletions_v78 "
                + "WHERE knowledge_base_id=? AND state='DELETING' AND attempts=1", base))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM knowledge_documents WHERE id=?", document))
                .isEqualTo(1);
        parentWorker().poll();
        assertThat(baseDeletionState(accepted.operationId()))
                .isEqualTo("DELETING");

        // Fast-forward only the transient child's retry in disposable test DB.
        jdbcTemplate.update("UPDATE knowledge_document_deletions_v78 "
                + "SET next_attempt_at=clock_timestamp() WHERE knowledge_base_id=?", base);
        childWorker(storage).poll();
        assertThat(purgeCalls.get()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM knowledge_document_deletions_v78 "
                + "WHERE knowledge_base_id=? AND state='DELETED'", base)).isEqualTo(1);

        jdbcTemplate.update("UPDATE knowledge_base_deletions_v82 "
                + "SET next_attempt_at=clock_timestamp() WHERE id=?",
                accepted.operationId());
        parentWorker().poll();
        assertThat(baseDeletionState(accepted.operationId()))
                .isEqualTo("DELETED");
    }

    private UUID createAdmin() {
        UUID id = UUID.randomUUID();
        insertUser(id, PLATFORM_ORGANIZATION_ID,
                "base-e2e-" + id + "@example.test", true, "ADMIN", Instant.now());
        return id;
    }

    private UUID createBase(UUID actor) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO knowledge_bases(id, organization_id, name, visibility,
                                            enabled, created_by_user_id, version)
                VALUES (?, ?, ?, 'ORGANIZATION', true, ?, 0)
                """, id, PLATFORM_ORGANIZATION_ID, "Base E2E " + id, actor);
        return id;
    }

    private UUID createDocument(UUID actor, UUID base) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO knowledge_documents(id, organization_id, knowledge_base_id,
                                                name, enabled, created_by_user_id, version)
                VALUES (?, ?, ?, ?, true, ?, 0)
                """, id, PLATFORM_ORGANIZATION_ID, base, "Document E2E " + id, actor);
        return id;
    }

    private void createVersion(UUID actor, UUID base, UUID document, int number, String key) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_document_versions(id, organization_id, knowledge_base_id,
                    document_id, version_number, original_filename, media_type,
                    size_bytes, sha256, storage_key, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, 'text/plain', 10, ?, ?, ?)
                """, UUID.randomUUID(), PLATFORM_ORGANIZATION_ID, base, document,
                number, "file-" + number + ".txt", "a".repeat(64), key, actor);
    }

    private KnowledgeBaseDeletionService deletionService() {
        return new KnowledgeBaseDeletionService(jdbcTemplate, audit, true, true, true, true);
    }

    private KnowledgeBaseDeletionResponse accept(UUID base, String key, SafeAiUserPrincipal admin) {
        return new TransactionTemplate(transactionManager).execute(tx ->
                deletionService().accept(base, 0L, key, admin));
    }

    private SafeAiUserPrincipal principal(UUID userId) {
        return SafeAiUserPrincipal.accessTokenPrincipal(userId, PLATFORM_ORGANIZATION_ID,
                0L, 0L, Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private KnowledgeBaseDeletionExecutor parentWorker() {
        return new KnowledgeBaseDeletionExecutor(jdbcTemplate, transactionManager, true, null, KnowledgeErasureTestJournal.INSTANCE);
    }

    private KnowledgeDocumentDeletionExecutor childWorker(ObjectStorage storage) {
        return new KnowledgeDocumentDeletionExecutor(jdbcTemplate, transactionManager, storage, null, KnowledgeErasureTestJournal.INSTANCE);
    }

    private String baseDeletionState(UUID operation) {
        return jdbcTemplate.queryForObject("""
                SELECT state
                FROM public.knowledge_base_deletions_v82
                WHERE id = ?
                """, String.class, operation);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }

    private static ByteArrayInputStream bytes(String data) {
        return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Item> exactVersions(MinioClient client, String bucket, String key)
            throws Exception {
        List<Item> result = new ArrayList<>();
        for (Result<Item> page : client.listObjects(ListObjectsArgs.builder()
                .bucket(bucket).prefix(key).includeVersions(true).recursive(true).build())) {
            Item item = page.get();
            if (key.equals(item.objectName())) result.add(item);
        }
        return result;
    }

    private static List<String> versionIds(MinioClient client, String bucket, String key)
            throws Exception {
        return exactVersions(client, bucket, key).stream().map(Item::versionId).toList();
    }
}
