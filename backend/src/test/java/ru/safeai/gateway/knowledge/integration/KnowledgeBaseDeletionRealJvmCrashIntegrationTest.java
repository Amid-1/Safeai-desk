package ru.safeai.gateway.knowledge.integration;

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
import org.springframework.core.env.Environment;
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
import ru.safeai.gateway.knowledge.service.KnowledgeBaseDeletionService;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageProperties;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageType;
import ru.safeai.gateway.knowledge.storage.S3ObjectStorage;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forces an actual OS-process kill between verified S3 purge and DB commit,
 * then starts a *different JVM* to recover the V78/V82 operation.
 * PostgreSQL and MinIO are disposable Testcontainers; no local app flags changed.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
class KnowledgeBaseDeletionRealJvmCrashIntegrationTest
        extends AbstractPostgresIntegrationTest {

    private static final String ACCESS_KEY = "safeai";
    private static final String SECRET_KEY = "safeai-local-change-me";
    private static final String PURGED_MARKER_PREFIX = "SAFEAI_TEST_PURGED ";

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
    @Autowired private Environment environment;

    @Test
    void actualJvmKillAfterVerifiedPurgeThenSecondJvmRecovers() throws Exception {
        // The Spring context must not have enabled any erasure executor.
        for (String flag : List.of(
                "safeai.knowledge.deletion.accept-enabled",
                "safeai.knowledge.deletion.executor-enabled",
                "safeai.knowledge.base-deletion.accept-enabled",
                "safeai.knowledge.base-deletion.executor-enabled")) {
            assertThat(environment.getProperty(flag, Boolean.class, false))
                    .as("Spring test feature flag must remain OFF: " + flag).isFalse();
        }
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(MINIO.isRunning()).isTrue();
        assertThat(POSTGRES.getJdbcUrl()).contains("/safeai_test");

        UUID actor = UUID.randomUUID();
        UUID base = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID neighbor = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        String key = "safeai-e2e-kill/" + nonce + "/target";
        String neighborKey = "safeai-e2e-kill/" + nonce + "/neighbor";
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        String bucket = "safeai-process-crash-" + UUID.randomUUID().toString().replace("-", "");

        insertUser(actor, PLATFORM_ORGANIZATION_ID,
                "real-kill-" + actor + "@example.test", true, "ADMIN", Instant.now());
        createBase(actor, base);
        createBase(actor, neighbor);
        jdbcTemplate.update("""
                INSERT INTO knowledge_documents(id, organization_id, knowledge_base_id,
                    name, enabled, created_by_user_id, version)
                VALUES (?, ?, ?, ?, true, ?, 0)
                """, document, PLATFORM_ORGANIZATION_ID, base,
                "Real kill " + document, actor);
        jdbcTemplate.update("""
                INSERT INTO knowledge_document_versions(id, organization_id, knowledge_base_id,
                    document_id, version_number, original_filename, media_type,
                    size_bytes, sha256, storage_key, created_by_user_id)
                VALUES (?, ?, ?, ?, 1, 'crash.txt', 'text/plain', 10, ?, ?, ?)
                """, UUID.randomUUID(), PLATFORM_ORGANIZATION_ID, base,
                document, "a".repeat(64), key, actor);

        try (MinioClient client = MinioClient.builder().endpoint(endpoint)
                .credentials(ACCESS_KEY, SECRET_KEY).build()) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            client.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(bucket)
                    .config(new VersioningConfiguration(
                            VersioningConfiguration.Status.ENABLED, false)).build());
            S3ObjectStorage storage = new S3ObjectStorage(new KnowledgeStorageProperties(
                    KnowledgeStorageType.S3, null, 26_214_400L,
                    endpoint, ACCESS_KEY, SECRET_KEY, bucket));
            try {
                storage.put(key, bytes("first target version"));
                storage.put(key, bytes("second target version"));
                storage.delete(key); // Must also physically purge this delete marker.
                storage.put(neighborKey, bytes("neighbor first"));
                storage.put(neighborKey, bytes("neighbor second"));
                List<String> neighborVersions = versionIds(client, bucket, neighborKey);
                assertThat(versions(client, bucket, key)).hasSize(3);
                assertThat(neighborVersions).hasSize(2);

                SafeAiUserPrincipal principal = SafeAiUserPrincipal.accessTokenPrincipal(
                        actor, PLATFORM_ORGANIZATION_ID, 0L, 0L,
                        Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                KnowledgeBaseDeletionService service = new KnowledgeBaseDeletionService(
                        jdbcTemplate, audit, true, true, true, true);
                KnowledgeBaseDeletionResponse accepted = new TransactionTemplate(transactionManager)
                        .execute(tx -> service.accept(base, 0L,
                                "real-jvm-kill-" + nonce, principal));
                assertThat(accepted).isNotNull();
                assertThat(accepted.state()).isEqualTo("DELETING");
                UUID childOperation = jdbcTemplate.queryForObject("""
                        SELECT id FROM knowledge_document_deletions_v78
                        WHERE knowledge_base_id=? AND document_id=?
                        """, UUID.class, base, document);
                assertThat(childOperation).isNotNull();

                Process crashed = startProcess("crash", endpoint, bucket, key, nonce);
                try (ExecutorService outputReader = Executors.newSingleThreadExecutor()) {
                    Future<String> purged = outputReader.submit(
                            () -> waitForPurgeMarker(crashed, nonce));
                    assertThat(purged.get(45, TimeUnit.SECONDS))
                            .isEqualTo(PURGED_MARKER_PREFIX + nonce);
                    assertThat(crashed.isAlive()).as("First JVM is paused after purge").isTrue();
                    assertThat(versions(client, bucket, key)).isEmpty();
                    assertThat(versionIds(client, bucket, neighborKey))
                            .containsExactlyElementsOf(neighborVersions);
                    // The first JVM has not reached the PostgreSQL finalization transaction.
                    assertThat(childState(childOperation)).isEqualTo("DELETING");
                    assertThat(baseState(accepted.operationId())).isEqualTo("DELETING");
                    assertThat(count("SELECT count(*) FROM knowledge_documents WHERE id=?", document))
                            .isEqualTo(1);
                    assertThat(count("SELECT count(*) FROM knowledge_document_erasure_objects_v81 "
                            + "WHERE operation_id=? AND storage_key=?", childOperation, key))
                            .isEqualTo(1);
                    assertThat(count("SELECT count(*) FROM knowledge_bases "
                            + "WHERE id=? AND lifecycle_state='DELETING' AND NOT enabled", base))
                            .isEqualTo(1);

                    // Actual OS-level forceful termination, not an injected IOException.
                    crashed.destroyForcibly();
                    assertThat(crashed.waitFor(15, TimeUnit.SECONDS)).isTrue();
                    assertThat(crashed.isAlive()).isFalse();
                    assertThat(childState(childOperation)).isEqualTo("DELETING");
                    assertThat(count("SELECT count(*) FROM knowledge_document_erasure_objects_v81 "
                            + "WHERE operation_id=?", childOperation)).isEqualTo(1);
                } finally {
                    if (crashed.isAlive()) {
                        crashed.destroyForcibly();
                        crashed.waitFor(15, TimeUnit.SECONDS);
                    }
                    // Closing the Java 21 ExecutorService waits for its reader task
                    // after the child JVM has been terminated above.
                }

                // A real five-minute lease will eventually expire. Advance only
                // this disposable Testcontainers operation so the test is deterministic.
                jdbcTemplate.update("""
                        UPDATE knowledge_document_deletions_v78
                        SET lease_until=clock_timestamp()-interval '1 second',
                            next_attempt_at=clock_timestamp()
                        WHERE id=? AND state='DELETING'
                        """, childOperation);
                Process restarted = startProcess("recover", endpoint, bucket, key, nonce);
                try {
                    assertThat(restarted.waitFor(45, TimeUnit.SECONDS))
                            .as("Second JVM must terminate after recovery").isTrue();
                    String output = new String(restarted.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8);
                    assertThat(restarted.exitValue()).as(output).isZero();
                    assertThat(output).contains("SAFEAI_TEST_RECOVERED " + nonce);
                } finally {
                    if (restarted.isAlive()) {
                        restarted.destroyForcibly();
                        restarted.waitFor(15, TimeUnit.SECONDS);
                    }
                }
                assertThat(childState(childOperation)).isEqualTo("DELETED");
                assertThat(baseState(accepted.operationId())).isEqualTo("DELETED");
                assertThat(count("SELECT count(*) FROM knowledge_bases WHERE id=?", base)).isZero();
                assertThat(count("SELECT count(*) FROM knowledge_documents WHERE id=?", document))
                        .isZero();
                assertThat(count("SELECT count(*) FROM knowledge_document_versions "
                        + "WHERE knowledge_base_id=?", base)).isZero();
                assertThat(count("SELECT count(*) FROM knowledge_document_erasure_objects_v81 "
                        + "WHERE operation_id=?", childOperation)).isZero();
                assertThat(count("SELECT count(*) FROM knowledge_base_identities_v82 WHERE id=?", base))
                        .isEqualTo(1);
                assertThat(count("SELECT count(*) FROM knowledge_bases "
                        + "WHERE id=? AND lifecycle_state='AVAILABLE' AND enabled", neighbor))
                        .isEqualTo(1);
                assertThat(versions(client, bucket, key)).isEmpty();
                assertThat(versionIds(client, bucket, neighborKey))
                        .containsExactlyElementsOf(neighborVersions);
            } finally {
                ReflectionTestUtils.invokeMethod(storage, "closeClient");
            }
        }
    }

    private void createBase(UUID actor, UUID base) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_bases(id, organization_id, name, visibility,
                                            enabled, created_by_user_id, version)
                VALUES (?, ?, ?, 'ORGANIZATION', true, ?, 0)
                """, base, PLATFORM_ORGANIZATION_ID, "Real process " + base, actor);
    }

    private Process startProcess(String mode, String endpoint, String bucket,
                                 String key, UUID nonce) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Cannot find test JVM classpath");
        }
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
                KnowledgeDeletionWorkerProcessMain.class.getName(), mode);
        builder.redirectErrorStream(true);
        builder.environment().put("SAFEAI_SIGKILL_TEST_GUARD", "DISPOSABLE_TESTCONTAINERS_ONLY");
        builder.environment().put("SAFEAI_SIGKILL_TEST_JDBC_URL", POSTGRES.getJdbcUrl());
        builder.environment().put("SAFEAI_SIGKILL_TEST_DB_USER", POSTGRES.getUsername());
        builder.environment().put("SAFEAI_SIGKILL_TEST_DB_PASSWORD", POSTGRES.getPassword());
        builder.environment().put("SAFEAI_SIGKILL_TEST_S3_ENDPOINT", endpoint);
        builder.environment().put("SAFEAI_SIGKILL_TEST_S3_BUCKET", bucket);
        builder.environment().put("SAFEAI_SIGKILL_TEST_S3_USER", ACCESS_KEY);
        builder.environment().put("SAFEAI_SIGKILL_TEST_S3_PASSWORD", SECRET_KEY);
        builder.environment().put("SAFEAI_SIGKILL_TEST_KEY", key);
        builder.environment().put("SAFEAI_SIGKILL_TEST_NONCE", nonce.toString());
        return builder.start();
    }

    private static String waitForPurgeMarker(Process process, UUID nonce)
            throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals(PURGED_MARKER_PREFIX + nonce)) {
                    return line;
                }
                if (output.length() < 12_000) output.append(line).append('\n');
            }
        }
        throw new IllegalStateException("Child JVM terminated before verified S3 purge. Output:\n"
                + output);
    }

    private String childState(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM knowledge_document_deletions_v78 WHERE id=?",
                String.class, id);
    }

    private String baseState(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM knowledge_base_deletions_v82 WHERE id=?",
                String.class, id);
    }

    private int count(String sql, Object... args) {
        Integer number = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return number == null ? -1 : number;
    }

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Item> versions(MinioClient client, String bucket, String key)
            throws Exception {
        List<Item> items = new ArrayList<>();
        for (Result<Item> result : client.listObjects(ListObjectsArgs.builder()
                .bucket(bucket).prefix(key).includeVersions(true).recursive(true).build())) {
            Item item = result.get();
            if (key.equals(item.objectName())) items.add(item);
        }
        return items;
    }

    private static List<String> versionIds(MinioClient client, String bucket, String key)
            throws Exception {
        return versions(client, bucket, key).stream().map(Item::versionId).toList();
    }
}
