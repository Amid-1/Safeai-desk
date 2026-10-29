package ru.safeai.gateway.knowledge.integration;

import io.minio.EnableObjectLegalHoldArgs;
import io.minio.GetBucketVersioningArgs;
import io.minio.GetObjectRetentionArgs;
import io.minio.IsObjectLegalHoldEnabledArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.SetObjectRetentionArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import io.minio.messages.VersioningConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
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
import ru.safeai.gateway.knowledge.storage.StoredObject;
import ru.safeai.gateway.knowledge.storage.S3ObjectStorage;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the real S3 Object Lock -> V78 document -> V82 base failure path.
 *
 * <p>Only disposable Testcontainers PostgreSQL and S3 are used. No Spring
 * deletion feature flags are enabled. This is a regression test, NOT a test
 * that changes any stored production retention setting.</p>
 *
 * <p>Unlike a synthetic BLOCKED_BY_RETENTION update, these tests require the
 * provider to actually reject permanent version deletion. A failed assertion
 * can reveal a genuine error-code classification defect in the executor.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
class KnowledgeBaseObjectLockIntegrationTest extends AbstractPostgresIntegrationTest {

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

    @Autowired
    private AuditEventService audit;

    @Test
    void complianceRetentionMustBlockPhysicalBaseErasure() throws Exception {
        assertBlockedEndToEnd(false);
    }

    @Test
    void legalHoldMustBlockPhysicalBaseErasure() throws Exception {
        assertBlockedEndToEnd(true);
    }

    @Test
    void genericInvalidRequestWithoutVerifiedObjectLockRemainsPending() {
        UUID actor = UUID.randomUUID();
        UUID base = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        String key = "object-lock/negative/" + UUID.randomUUID();
        createFixture(actor, base, document, key);
        SafeAiUserPrincipal admin = SafeAiUserPrincipal.accessTokenPrincipal(
                actor, PLATFORM_ORGANIZATION_ID, 0L, 0L,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        KnowledgeBaseDeletionService service = new KnowledgeBaseDeletionService(
                jdbcTemplate, audit, true, true, true, true);
        KnowledgeBaseDeletionResponse accepted = new TransactionTemplate(transactionManager)
                .execute(tx -> service.accept(base, 0L, "no-lock-" + UUID.randomUUID(), admin));
        assertThat(accepted).isNotNull();
        KnowledgeDocumentDeletionExecutor worker = invalidRequestWorker(key);
        worker.poll();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM public.knowledge_document_deletions_v78
                WHERE knowledge_base_id = ? AND document_id = ?
                """, String.class, base, document)).isEqualTo("DELETING");
        // Force retries only in disposable Testcontainers PostgreSQL. After five
        // attempts an unverified provider failure MUST be NEEDS_REVIEW,
        // never BLOCKED_BY_RETENTION or DELETED.
        for (int attempt = 2; attempt <= 5; attempt++) {
            jdbcTemplate.update("""
                    UPDATE public.knowledge_document_deletions_v78
                    SET next_attempt_at = clock_timestamp()
                    WHERE knowledge_base_id = ? AND document_id = ?
                      AND state = 'DELETING'
                    """, base, document);
            worker.poll();
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM public.knowledge_document_deletions_v78
                WHERE knowledge_base_id = ? AND document_id = ?
                """, String.class, base, document)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.knowledge_document_erasure_objects_v81 i
                JOIN public.knowledge_document_deletions_v78 d ON d.id = i.operation_id
                WHERE d.knowledge_base_id = ? AND i.storage_key = ?
                """, Integer.class, base, key)).isEqualTo(1);
        new KnowledgeBaseDeletionExecutor(jdbcTemplate, transactionManager, true).poll();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM public.knowledge_base_deletions_v82 WHERE id = ?
                """, String.class, accepted.operationId())).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.knowledge_bases
                WHERE id = ? AND lifecycle_state = 'DELETING' AND NOT enabled
                """, Integer.class, base)).isEqualTo(1);
    }

    private KnowledgeDocumentDeletionExecutor invalidRequestWorker(String expectedKey) {
        ObjectStorage refused = new ObjectStorage() {
            @Override public void put(String key, InputStream contents) { }
            @Override public StoredObject get(String key) { return null; }
            @Override public void delete(String key) { }
            @Override public void purgeAllVersionsVerified(String key) throws IOException {
                assertThat(key).isEqualTo(expectedKey);
                throw new IOException("InvalidRequest without verified retention");
            }
        };
        return new KnowledgeDocumentDeletionExecutor(jdbcTemplate, transactionManager, refused);
    }

    private void assertBlockedEndToEnd(boolean legalHold) throws Exception {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        String bucket = "safeai-lock-" + UUID.randomUUID().toString().replace("-", "");
        String key = "object-lock/" + UUID.randomUUID() + "/protected.txt";
        String neighbor = key + "-untouched";

        try (MinioClient client = MinioClient.builder()
                .endpoint(endpoint).credentials(ACCESS_KEY, SECRET_KEY).build()) {
            // Object Lock MUST be enabled at bucket creation, not afterwards.
            client.makeBucket(MakeBucketArgs.builder()
                    .bucket(bucket).objectLock(true).build());
            assertThat(client.getBucketVersioning(GetBucketVersioningArgs.builder()
                    .bucket(bucket).build()).status())
                    .isEqualTo(VersioningConfiguration.Status.ENABLED);

            S3ObjectStorage storage = new S3ObjectStorage(new KnowledgeStorageProperties(
                    KnowledgeStorageType.S3, null, 26_214_400L,
                    endpoint, ACCESS_KEY, SECRET_KEY, bucket));
            try {
                storage.put(key, bytes("document-protected-by-object-lock"));
                storage.put(neighbor, bytes("unrelated-document"));
                String protectedVersion = soleVersion(client, bucket, key).versionId();
                String neighborVersion = soleVersion(client, bucket, neighbor).versionId();
                assertThat(protectedVersion).isNotBlank();

                if (legalHold) {
                    client.enableObjectLegalHold(EnableObjectLegalHoldArgs.builder()
                            .bucket(bucket).object(key).versionId(protectedVersion).build());
                    assertThat(client.isObjectLegalHoldEnabled(IsObjectLegalHoldEnabledArgs.builder()
                            .bucket(bucket).object(key).versionId(protectedVersion).build()))
                            .isTrue();
                } else {
                    client.setObjectRetention(SetObjectRetentionArgs.builder()
                            .bucket(bucket).object(key).versionId(protectedVersion)
                            .config(new Retention(RetentionMode.COMPLIANCE,
                                    ZonedDateTime.now(ZoneOffset.UTC).plusDays(1)))
                            .build());
                    Retention actual = client.getObjectRetention(GetObjectRetentionArgs.builder()
                            .bucket(bucket).object(key).versionId(protectedVersion).build());
                    assertThat(actual.mode()).isEqualTo(RetentionMode.COMPLIANCE);
                    assertThat(actual.retainUntilDate().toInstant()).isAfter(Instant.now());
                }

                // Independently confirm that the provider really denies a
                // version-aware purge. Do not accept an emulator that ignores WORM.
                IOException denied = org.junit.jupiter.api.Assertions.assertThrows(
                        IOException.class, () -> storage.purgeAllVersionsVerified(key));
                String providerCode = providerErrorCode(denied);
                assertThat(providerCode)
                        .as("MinIO must return an explicit provider error for a WORM-locked version")
                        .isNotBlank();
                assertThat(exactVersions(client, bucket, key).stream()
                        .map(Item::versionId)).contains(protectedVersion);

                UUID actor = UUID.randomUUID();
                UUID base = UUID.randomUUID();
                UUID document = UUID.randomUUID();
                createFixture(actor, base, document, key);
                SafeAiUserPrincipal admin = SafeAiUserPrincipal.accessTokenPrincipal(
                        actor, PLATFORM_ORGANIZATION_ID, 0L, 0L,
                        Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                KnowledgeBaseDeletionService service = new KnowledgeBaseDeletionService(
                        jdbcTemplate, audit, true, true, true, true);
                String idempotencyKey = "lock-e2e-" + UUID.randomUUID();
                KnowledgeBaseDeletionResponse accepted = new TransactionTemplate(transactionManager)
                        .execute(tx -> service.accept(base, 0L, idempotencyKey, admin));
                assertThat(accepted).isNotNull();
                assertThat(accepted.state()).isEqualTo("DELETING");

                new KnowledgeDocumentDeletionExecutor(jdbcTemplate, transactionManager,
                        storage).poll();
                String childState = jdbcTemplate.queryForObject("""
                        SELECT state FROM public.knowledge_document_deletions_v78
                        WHERE knowledge_base_id = ? AND document_id = ?
                        """, String.class, base, document);

                // This assertion is intentionally strict. In some S3 providers
                // compliance/legal-hold deletion yields AccessDenied (403), not
                // ObjectLocked; the existing executor may misclassify this as retry.
                assertThat(childState)
                        .as("Actual S3 provider error: %s; protected version must result in "
                                + "BLOCKED_BY_RETENTION, not retry/DELETED", providerCode)
                        .isEqualTo("BLOCKED_BY_RETENTION");

                new KnowledgeBaseDeletionExecutor(jdbcTemplate, transactionManager,
                        true).poll();
                String parentState = jdbcTemplate.queryForObject("""
                        SELECT state FROM public.knowledge_base_deletions_v82
                        WHERE id = ?
                        """, String.class, accepted.operationId());
                assertThat(parentState).isEqualTo("BLOCKED_BY_RETENTION");
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM public.knowledge_bases
                        WHERE id = ? AND lifecycle_state = 'DELETING' AND NOT enabled
                        """, Integer.class, base)).isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM public.knowledge_documents
                        WHERE id = ? AND lifecycle_state = 'DELETING' AND NOT enabled
                        """, Integer.class, document)).isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM public.knowledge_document_erasure_objects_v81 erasure
                        JOIN public.knowledge_document_deletions_v78 operation
                          ON operation.id = erasure.operation_id
                        WHERE operation.knowledge_base_id = ? AND erasure.storage_key = ?
                        """, Integer.class, base, key)).isEqualTo(1);
                assertThat(exactVersions(client, bucket, key).stream()
                        .map(Item::versionId)).contains(protectedVersion);
                assertThat(exactVersions(client, bucket, neighbor).stream()
                        .map(Item::versionId)).containsExactly(neighborVersion);
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM public.knowledge_base_deletions_v82
                        WHERE id = ? AND completed_at IS NULL
                        """, Integer.class, accepted.operationId())).isEqualTo(1);
            } finally {
                // No access modifier changes to production S3ObjectStorage.
                ReflectionTestUtils.invokeMethod(storage, "closeClient");
            }
        }
    }

    private void createFixture(UUID actor, UUID base, UUID document, String key) {
        insertUser(actor, PLATFORM_ORGANIZATION_ID,
                "object-lock-" + actor + "@example.test", true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_bases(
                  id, organization_id, name, visibility, enabled, created_by_user_id, version)
                VALUES (?, ?, ?, 'ORGANIZATION', true, ?, 0)
                """, base, PLATFORM_ORGANIZATION_ID, "Object Lock E2E " + base, actor);
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_documents(
                  id, organization_id, knowledge_base_id, name, enabled, created_by_user_id, version)
                VALUES (?, ?, ?, ?, true, ?, 0)
                """, document, PLATFORM_ORGANIZATION_ID, base,
                "Protected document " + document, actor);
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_document_versions(
                  id, organization_id, knowledge_base_id, document_id, version_number,
                  original_filename, media_type, size_bytes, sha256, storage_key, created_by_user_id)
                VALUES (?, ?, ?, ?, 1, 'protected.txt', 'text/plain', 1, ?, ?, ?)
                """, UUID.randomUUID(), PLATFORM_ORGANIZATION_ID, base, document,
                "a".repeat(64), key, actor);
    }

    private static String providerErrorCode(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ErrorResponseException s3 && s3.errorResponse() != null) {
                return s3.errorResponse().code();
            }
        }
        return "";
    }

    private static Item soleVersion(MinioClient client, String bucket, String key)
            throws Exception {
        List<Item> versions = exactVersions(client, bucket, key);
        assertThat(versions).as("Expected one physical version for %s", key).hasSize(1);
        return versions.getFirst();
    }

    private static List<Item> exactVersions(MinioClient client, String bucket, String key)
            throws Exception {
        List<Item> items = new ArrayList<>();
        for (Result<Item> result : client.listObjects(ListObjectsArgs.builder()
                .bucket(bucket).prefix(key).includeVersions(true).recursive(true).build())) {
            Item item = result.get();
            if (key.equals(item.objectName())) items.add(item);
        }
        return items;
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
