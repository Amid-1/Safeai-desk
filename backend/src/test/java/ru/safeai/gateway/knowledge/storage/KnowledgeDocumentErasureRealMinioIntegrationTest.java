package ru.safeai.gateway.knowledge.storage;

import io.minio.GetBucketVersioningArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.SetBucketVersioningArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import io.minio.messages.VersioningConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionAcceptanceService;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionExecutor;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL (AbstractPostgresIntegrationTest) + real versioned MinIO.
 * Exercises accepted deletion -> durable inventory -> physical version purge ->
 * fenced DB cleanup -> DELETED, including preservation of an adjacent key.
 * This test is NOT a retention/legal-hold or concurrent-reader certification.
 */
@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeDocumentErasureRealMinioIntegrationTest extends AbstractPostgresIntegrationTest {
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
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).forStatusCode(200));

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private KnowledgeDocumentDeletionAcceptanceService acceptance;

    @Test
    void acceptedDocumentIsPhysicallyErasedAndAuditedWithoutAffectingNeighbor() throws Exception {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        String bucket = "safeai-erasure-e2e-" + UUID.randomUUID().toString().replace("-", "");
        try (MinioClient client = MinioClient.builder()
                .endpoint(endpoint).credentials(ACCESS_KEY, SECRET_KEY).build()) {
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
                UUID actor = UUID.randomUUID();
                UUID base = UUID.randomUUID();
                UUID doc = UUID.randomUUID();
                UUID version = UUID.randomUUID();
                String key = "org/base/doc/" + UUID.randomUUID();
                String neighbor = key + "-other";

                storage.put(key, bytes("first"));
                storage.put(key, bytes("second"));
                storage.delete(key); // S3 creates a delete marker; both versions remain.
                storage.put(neighbor, bytes("untouched"));
                List<Item> historical = exact(client, bucket, key);
                assertThat(historical).hasSize(3);
                assertThat(historical.stream().filter(Item::isDeleteMarker).count()).isEqualTo(1);
                String historicalId = historical.stream()
                        .filter(item -> !item.isDeleteMarker()).findFirst().orElseThrow().versionId();
                assertThat(readVersion(client, bucket, key, historicalId)).isIn("first", "second");
                assertThat(exact(client, bucket, neighbor)).hasSize(1);

                insertUser(actor, PLATFORM_ORGANIZATION_ID,
                        "erasure-e2e-" + actor + "@example.test", true, "ADMIN", Instant.now());
                jdbcTemplate.update("""
                        INSERT INTO knowledge_bases(id, organization_id, name, visibility, enabled,
                                                    created_by_user_id, version)
                        VALUES (?, ?, ?, 'ORGANIZATION', true, ?, 0)
                        """, base, PLATFORM_ORGANIZATION_ID, "E2E deletion " + base, actor);
                jdbcTemplate.update("""
                        INSERT INTO knowledge_documents(id, organization_id, knowledge_base_id,
                                                        name, enabled, created_by_user_id, version)
                        VALUES (?, ?, ?, 'Real storage erasure', true, ?, 0)
                        """, doc, PLATFORM_ORGANIZATION_ID, base, actor);
                jdbcTemplate.update("""
                        INSERT INTO knowledge_document_versions(id, organization_id, knowledge_base_id,
                                document_id, version_number, original_filename, media_type,
                                size_bytes, sha256, storage_key, created_by_user_id)
                        VALUES (?, ?, ?, ?, 1, 'original.txt', 'text/plain', 6, ?, ?, ?)
                        """, version, PLATFORM_ORGANIZATION_ID, base, doc,
                        "a".repeat(64), key, actor);

                SafeAiUserPrincipal admin = SafeAiUserPrincipal.accessTokenPrincipal(
                        actor, PLATFORM_ORGANIZATION_ID, 0L, 0L,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                var accepted = acceptance.accept(base, doc, 0,
                        "real-minio-" + UUID.randomUUID(), admin);
                assertThat(accepted.state()).isEqualTo("DELETING");
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT enabled FROM knowledge_documents WHERE id=?",
                        Boolean.class, doc)).isFalse();

                // Drive the actual executor against the real versioned bucket.
                new KnowledgeDocumentDeletionExecutor(jdbcTemplate, transactionManager, storage).poll();

                assertThat(acceptance.status(base, accepted.operationId(), admin).state())
                        .isEqualTo("DELETED");
                assertThat(exact(client, bucket, key)).isEmpty();
                assertThat(exact(client, bucket, neighbor)).hasSize(1);
                assertThat(readLatest(client, bucket, neighbor)).isEqualTo("untouched");
                assertThatThrownBy(() -> readVersion(client, bucket, key, historicalId))
                        .isInstanceOf(ErrorResponseException.class)
                        .satisfies(error -> assertThat(((ErrorResponseException) error)
                                .errorResponse().code())
                                .isIn("NoSuchVersion", "NoSuchKey", "NotFound"));

                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM knowledge_documents WHERE id=?",
                        Integer.class, doc)).isZero();
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM knowledge_document_versions WHERE document_id=?",
                        Integer.class, doc)).isZero();
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM knowledge_document_chunks WHERE document_id=?",
                        Integer.class, doc)).isZero();
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM knowledge_document_erasure_objects_v81 WHERE operation_id=?",
                        Integer.class, accepted.operationId())).isZero();
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM knowledge_document_version_identities_v80 WHERE document_id=?",
                        Integer.class, doc)).isEqualTo(1);
            } finally {
                storage.closeClient();
            }
        }
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Item> exact(MinioClient client, String bucket, String key) throws Exception {
        List<Item> items = new ArrayList<>();
        for (Result<Item> result : client.listObjects(ListObjectsArgs.builder()
                .bucket(bucket).prefix(key).recursive(true).includeVersions(true).build())) {
            Item item = result.get();
            if (key.equals(item.objectName())) items.add(item);
        }
        return items;
    }

    private static String readVersion(MinioClient client, String bucket,
                                      String key, String versionId) throws Exception {
        try (var response = client.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(key).versionId(versionId).build())) {
            return new String(response.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readLatest(MinioClient client, String bucket, String key) throws Exception {
        try (var response = client.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(key).build())) {
            return new String(response.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
