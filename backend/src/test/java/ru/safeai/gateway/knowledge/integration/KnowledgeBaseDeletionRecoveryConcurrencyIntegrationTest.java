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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.safeai.gateway.knowledge.testsupport.KnowledgeErasureTestJournal;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Crash-window and multi-worker fencing tests for V78/V81/V82.
 *
 * <p>Testcontainers PostgreSQL and MinIO are disposable. Production feature flags
 * remain disabled; test-created workers operate only on random test records.
 * The injected post-purge failure models the recovery boundary, not an actual
 * operating-system process kill.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
class KnowledgeBaseDeletionRecoveryConcurrencyIntegrationTest
        extends AbstractPostgresIntegrationTest {

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
    void realS3PurgeAlreadyCommittedButDatabaseNotCommittedIsSafelyRetried()
            throws Exception {
        Fixture target = fixture(true);
        String targetKey = "erasure-restart/" + UUID.randomUUID();
        String untouchedKey = "erasure-restart/untouched-" + UUID.randomUUID();
        addVersion(target, targetKey);
        KnowledgeBaseDeletionResponse accepted = accept(target);
        UUID childOperation = documentOperation(target);
        prioritizeTargetDeletion(target, childOperation);

        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        String bucket = "safeai-restart-" + UUID.randomUUID().toString().replace("-", "");
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
                storage.put(targetKey, bytes("first physical version"));
                storage.put(targetKey, bytes("second physical version"));
                storage.delete(targetKey); // Includes a delete marker in the inventory.
                storage.put(untouchedKey, bytes("unrelated object"));
                List<String> untouchedVersionIds = versionIds(client, bucket, untouchedKey);
                assertThat(exactVersions(client, bucket, targetKey)).hasSize(3);
                assertThat(untouchedVersionIds).hasSize(1);

                AtomicInteger calls = new AtomicInteger();
                ObjectStorage failureAfterVerifiedPurge = new ObjectStorage() {
                    @Override public void put(String key, InputStream body) { }
                    @Override public StoredObject get(String key) { return null; }
                    @Override public void delete(String key) { }
                    @Override public void purgeAllVersionsVerified(String key) throws IOException {
                        assertThat(key).isEqualTo(targetKey);
                        calls.incrementAndGet();
                        storage.purgeAllVersionsVerified(key);
                        // Реальный процесс может завершиться именно здесь. Имитируем
                        // сбой до фиксации результата удаления в PostgreSQL.
                        throw new IOException("injected failure after real verified S3 purge");
                    }
                };
                childWorker(failureAfterVerifiedPurge).poll();

                assertThat(calls.get()).isEqualTo(1);
                assertThat(exactVersions(client, bucket, targetKey)).isEmpty();
                assertThat(versionIds(client, bucket, untouchedKey))
                        .containsExactlyElementsOf(untouchedVersionIds);
                assertThat(childState(childOperation)).isEqualTo("DELETING");
                assertThat(count("SELECT attempts FROM knowledge_document_deletions_v78 "
                        + "WHERE id=?", childOperation)).isEqualTo(1);
                assertThat(baseState(accepted.operationId())).isEqualTo("DELETING");
                assertThat(count("SELECT count(*) FROM knowledge_documents WHERE id=?",
                        target.document())).isEqualTo(1);
                assertThat(count("SELECT count(*) FROM knowledge_document_versions "
                        + "WHERE document_id=?", target.document())).isEqualTo(1);
                assertThat(count("SELECT count(*) FROM knowledge_document_erasure_objects_v81 "
                        + "WHERE operation_id=? AND storage_key=?", childOperation, targetKey))
                        .isEqualTo(1);

                // Перезапущенный исполнитель читает сохранённую инвентаризацию,
                // повторяет подтверждённую очистку и только затем фиксирует результат.
                jdbcTemplate.update("UPDATE knowledge_document_deletions_v78 "
                        + "SET next_attempt_at=clock_timestamp() WHERE id=?", childOperation);
                childWorker(storage).poll();
                assertThat(childState(childOperation)).isEqualTo("DELETED");
                assertThat(count("SELECT count(*) FROM knowledge_document_erasure_objects_v81 "
                        + "WHERE operation_id=?", childOperation)).isZero();
                assertThat(exactVersions(client, bucket, targetKey)).isEmpty();
                assertThat(versionIds(client, bucket, untouchedKey))
                        .containsExactlyElementsOf(untouchedVersionIds);

                parentWorker().poll();
                assertThat(baseState(accepted.operationId())).isEqualTo("DELETED");
                assertThat(count("SELECT count(*) FROM knowledge_bases WHERE id=?",
                        target.base())).isZero();
                assertThat(count("SELECT count(*) FROM knowledge_base_identities_v82 WHERE id=?",
                        target.base())).isEqualTo(1);
            } finally {
                ReflectionTestUtils.invokeMethod(storage, "closeClient");
            }
        }
    }

    @Test
    void concurrentWorkersNeverClaimTheSameLiveDocumentLease() throws Exception {
        Fixture target = fixture(true);
        String key = "erasure-concurrent/" + UUID.randomUUID();
        addVersion(target, key);
        KnowledgeBaseDeletionResponse accepted = accept(target);
        UUID childOperation = documentOperation(target);
        prioritizeTargetDeletion(target, childOperation);
        CountDownLatch insidePurge = new CountDownLatch(1);
        CountDownLatch finishPurge = new CountDownLatch(1);
        AtomicInteger purges = new AtomicInteger();
        ObjectStorage blockingStorage = storage(key, () -> {
            purges.incrementAndGet();
            insidePurge.countDown();
            if (!finishPurge.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test purge latch timed out");
            }
        });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            try {
                Future<?> first = executor.submit(() -> childWorker(blockingStorage).poll());
                awaitPurgeOrReport(insidePurge, first, childOperation);
                assertThat(count("SELECT count(*) FROM knowledge_document_erasure_objects_v81 "
                        + "WHERE operation_id=? AND storage_key=?", childOperation, key))
                        .isEqualTo(1);
                UUID originalLease = jdbcTemplate.queryForObject(
                        "SELECT lease_token FROM knowledge_document_deletions_v78 WHERE id=?",
                        UUID.class, childOperation);
                assertThat(originalLease).isNotNull();

                // Второй независимый исполнитель не вправе захватить действующую аренду.
                childWorker(blockingStorage).poll();
                assertThat(purges.get()).isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT lease_token FROM knowledge_document_deletions_v78 WHERE id=?",
                        UUID.class, childOperation)).isEqualTo(originalLease);
                assertThat(childState(childOperation)).isEqualTo("DELETING");
                assertThat(baseState(accepted.operationId())).isEqualTo("DELETING");
                finishPurge.countDown();
                first.get(15, TimeUnit.SECONDS);
                assertThat(childState(childOperation)).isEqualTo("DELETED");
                assertThat(purges.get()).isEqualTo(1);
                parentWorker().poll();
                assertThat(baseState(accepted.operationId())).isEqualTo("DELETED");
            } finally {
                finishPurge.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void expiredLeaseFencesOldWorkerEvenAfterItHasEnteredStorage() throws Exception {
        Fixture target = fixture(true);
        String key = "erasure-fencing/" + UUID.randomUUID();
        addVersion(target, key);
        KnowledgeBaseDeletionResponse accepted = accept(target);
        UUID childOperation = documentOperation(target);
        prioritizeTargetDeletion(target, childOperation);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ObjectStorage fencedStorage = storage(key, () -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                if (!releaseFirst.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("old worker latch timed out");
                }
            }
        });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            try {
                Future<?> oldWorker = executor.submit(() -> childWorker(fencedStorage).poll());
                awaitPurgeOrReport(firstEntered, oldWorker, childOperation);
                UUID previousToken = jdbcTemplate.queryForObject(
                        "SELECT lease_token FROM knowledge_document_deletions_v78 WHERE id=?",
                        UUID.class, childOperation);
                assertThat(previousToken).isNotNull();
                assertThat(count("SELECT count(*) FROM knowledge_document_erasure_objects_v81 "
                        + "WHERE operation_id=? AND storage_key=?", childOperation, key))
                        .isEqualTo(1);

                // Истечение аренды имитируется только в одноразовой тестовой базе.
                jdbcTemplate.update("UPDATE knowledge_document_deletions_v78 "
                        + "SET lease_until=clock_timestamp()-interval '1 second', "
                        + "next_attempt_at=clock_timestamp() WHERE id=?", childOperation);
                childWorker(fencedStorage).poll();
                assertThat(calls.get()).isEqualTo(2);
                assertThat(childState(childOperation)).isEqualTo("DELETED");
                assertThat(count("SELECT count(*) FROM knowledge_documents WHERE id=?",
                        target.document())).isZero();
                assertThat(count("SELECT count(*) FROM knowledge_document_erasure_objects_v81 "
                        + "WHERE operation_id=?", childOperation)).isZero();

                // Старый исполнитель просыпается после нового: его устаревший
                // токен не должен изменять состояние или удалять дополнительные строки.
                releaseFirst.countDown();
                oldWorker.get(15, TimeUnit.SECONDS);
                assertThat(childState(childOperation)).isEqualTo("DELETED");
                assertThat(count("SELECT attempts FROM knowledge_document_deletions_v78 "
                        + "WHERE id=?", childOperation)).isEqualTo(2);
                assertThat(count("SELECT count(*) FROM knowledge_document_deletions_v78 "
                        + "WHERE id=? AND lease_token IS NULL AND state='DELETED'", childOperation))
                        .isEqualTo(1);
                assertThat(baseState(accepted.operationId())).isEqualTo("DELETING");
                parentWorker().poll();
                assertThat(baseState(accepted.operationId())).isEqualTo("DELETED");
            } finally {
                releaseFirst.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void parentCannotFinalizeUsingStolenLeaseToken() {
        Fixture target = fixture(false);
        Fixture untouched = fixture(false);
        KnowledgeBaseDeletionResponse accepted = accept(target);
        KnowledgeBaseDeletionExecutor oldWorker = parentWorker();
        Object staleClaim = ReflectionTestUtils.invokeMethod(oldWorker, "claim");
        assertThat(staleClaim).isNotNull();
        UUID oldToken = jdbcTemplate.queryForObject(
                "SELECT lease_token FROM knowledge_base_deletions_v82 WHERE id=?",
                UUID.class, accepted.operationId());
        assertThat(oldToken).isNotNull();

        UUID stolenToken = UUID.randomUUID();
        jdbcTemplate.update("UPDATE knowledge_base_deletions_v82 "
                + "SET lease_token=? WHERE id=?", stolenToken, accepted.operationId());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> ReflectionTestUtils.invokeMethod(
                        oldWorker, "finishOrReschedule", staleClaim)))
                .satisfies(error -> assertThat(error.getClass().getSimpleName())
                        .isEqualTo("LeaseLost"));
        assertThat(baseState(accepted.operationId())).isEqualTo("DELETING");
        assertThat(count("SELECT count(*) FROM knowledge_bases WHERE id=?", target.base()))
                .isEqualTo(1);

        // После истечения аренды новый исполнитель завершает операцию; соседняя база сохраняется.
        jdbcTemplate.update("UPDATE knowledge_base_deletions_v82 "
                + "SET lease_until=clock_timestamp()-interval '1 second', "
                + "next_attempt_at=clock_timestamp() WHERE id=?", accepted.operationId());
        parentWorker().poll();
        assertThat(baseState(accepted.operationId())).isEqualTo("DELETED");
        assertThat(count("SELECT count(*) FROM knowledge_bases WHERE id=?", target.base()))
                .isZero();
        assertThat(count("SELECT count(*) FROM knowledge_bases WHERE id=? "
                + "AND lifecycle_state='AVAILABLE' AND enabled", untouched.base()))
                .isEqualTo(1);
    }

    /**
     * Помещает только операцию данного сценария в начало реальной очереди V78.
     *
     * <p>Тестовый PostgreSQL общий. В нём могут оставаться доказательства других
     * операций, которые намеренно запрещено удалять триггерами V78/V82.
     * В production poll() обрабатывает не более пяти заявок за вызов.
     * Без приоритета сценарий конкуренции иногда проверял бы чужую заявку,
     * а целевой purge вообще не был бы вызван.</p>
     *
     * <p>Не меняем состояние операции, её владельца, токен аренды, попытки,
     * защитные триггеры или алгоритм захвата. Корректируем только разрешённое
     * время очередной попытки у ещё не арендованной тестовой операции.</p>
     */
    private void prioritizeTargetDeletion(Fixture target, UUID childOperation) {
        int updated = jdbcTemplate.update("""
                UPDATE public.knowledge_document_deletions_v78
                   SET next_attempt_at = (
                       SELECT COALESCE(MIN(next_attempt_at), clock_timestamp())
                              - interval '1 day'
                         FROM public.knowledge_document_deletions_v78
                        WHERE state = 'DELETING'
                   ),
                       updated_at = clock_timestamp()
                 WHERE id = ?
                   AND organization_id = ?
                   AND knowledge_base_id = ?
                   AND document_id = ?
                   AND state = 'DELETING'
                   AND lease_token IS NULL
                   AND lease_until IS NULL
                """, childOperation, PLATFORM_ORGANIZATION_ID,
                target.base(), target.document());
        assertThat(updated)
                .as("В очередь разрешено переместить ровно одну неарендованную тестовую операцию")
                .isEqualTo(1);
    }

    /**
     * Не скрывает остановку фонового исполнителя за сообщением «ожидалось true».
     * При отсутствии входа в S3 сообщает состояние, счётчик попыток,
     * код ошибки и времена аренды из PostgreSQL.
     */
    private void awaitPurgeOrReport(CountDownLatch latch, Future<?> worker, UUID operationId)
            throws Exception {
        if (latch.await(15, TimeUnit.SECONDS)) {
            return;
        }
        // Если фоновый процесс завершился с исключением, возвращаем исходную
        // причину, а не заменяем её ошибкой тайм-аута.
        if (worker.isDone()) {
            worker.get(1, TimeUnit.SECONDS);
        }
        var state = jdbcTemplate.queryForMap("""
                SELECT state, attempts, last_error_code, next_attempt_at,
                       lease_token, lease_until
                  FROM public.knowledge_document_deletions_v78
                 WHERE id = ?
                """, operationId);
        throw new AssertionError(
                "Удаление не дошло до подтверждённого S3 purge. "
                        + "Операция: " + operationId + ", состояние: " + state
                        + ", worker.isDone(): " + worker.isDone());
    }

    private Fixture fixture(boolean withDocument) {
        UUID actor = UUID.randomUUID();
        UUID base = UUID.randomUUID();
        insertUser(actor, PLATFORM_ORGANIZATION_ID,
                "recovery-" + actor + "@example.test", true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
                INSERT INTO knowledge_bases(id,organization_id,name,visibility,enabled,
                                            created_by_user_id,version)
                VALUES(?,?,?,'ORGANIZATION',true,?,0)
                """, base, PLATFORM_ORGANIZATION_ID, "Recovery " + base, actor);
        UUID doc = null;
        if (withDocument) {
            doc = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO knowledge_documents(id,organization_id,knowledge_base_id,
                                                    name,enabled,created_by_user_id,version)
                    VALUES(?,?,?,?,true,?,0)
                    """, doc, PLATFORM_ORGANIZATION_ID, base, "Recovery doc " + doc, actor);
        }
        return new Fixture(actor, base, doc);
    }

    private void addVersion(Fixture fixture, String key) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_document_versions(id,organization_id,knowledge_base_id,
                    document_id,version_number,original_filename,media_type,
                    size_bytes,sha256,storage_key,created_by_user_id)
                VALUES(?,?,?,?,1,'recovery.txt','text/plain',10,?,?,?)
                """, UUID.randomUUID(), PLATFORM_ORGANIZATION_ID, fixture.base(),
                fixture.document(), "a".repeat(64), key, fixture.actor());
    }

    private KnowledgeBaseDeletionResponse accept(Fixture fixture) {
        SafeAiUserPrincipal admin = SafeAiUserPrincipal.accessTokenPrincipal(
                fixture.actor(), PLATFORM_ORGANIZATION_ID, 0L, 0L,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        KnowledgeBaseDeletionService service = new KnowledgeBaseDeletionService(
                jdbcTemplate, audit, true, true, true, true);
        KnowledgeBaseDeletionResponse result = new TransactionTemplate(transactionManager)
                .execute(tx -> service.accept(fixture.base(), 0L,
                        "recovery-" + UUID.randomUUID(), admin));
        assertThat(result).isNotNull();
        assertThat(result.state()).isEqualTo("DELETING");
        return result;
    }

    private UUID documentOperation(Fixture fixture) {
        UUID operation = jdbcTemplate.queryForObject("""
                SELECT id FROM knowledge_document_deletions_v78
                WHERE knowledge_base_id=? AND document_id=?
                """, UUID.class, fixture.base(), fixture.document());
        assertThat(operation).isNotNull();
        return operation;
    }

    private KnowledgeDocumentDeletionExecutor childWorker(ObjectStorage store) {
        return new KnowledgeDocumentDeletionExecutor(jdbcTemplate, transactionManager, store, null, KnowledgeErasureTestJournal.INSTANCE);
    }

    private KnowledgeBaseDeletionExecutor parentWorker() {
        return new KnowledgeBaseDeletionExecutor(jdbcTemplate, transactionManager, true, null, KnowledgeErasureTestJournal.INSTANCE);
    }

    private String childState(UUID operation) {
        return jdbcTemplate.queryForObject("""
                SELECT state FROM knowledge_document_deletions_v78 WHERE id=?
                """, String.class, operation);
    }

    private String baseState(UUID operation) {
        return jdbcTemplate.queryForObject("""
                SELECT state FROM knowledge_base_deletions_v82 WHERE id=?
                """, String.class, operation);
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return value == null ? -1 : value;
    }

    private static ObjectStorage storage(String expectedKey, InterruptibleAction action) {
        return new ObjectStorage() {
            @Override public void put(String key, InputStream content) { }
            @Override public StoredObject get(String key) { return null; }
            @Override public void delete(String key) { }
            @Override public void purgeAllVersionsVerified(String key) throws IOException {
                assertThat(key).isEqualTo(expectedKey);
                try {
                    action.run();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test interrupted", interrupted);
                }
            }
        };
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Item> exactVersions(MinioClient client, String bucket, String key)
            throws Exception {
        List<Item> items = new ArrayList<>();
        for (Result<Item> result : client.listObjects(ListObjectsArgs.builder()
                .bucket(bucket).prefix(key).recursive(true).includeVersions(true).build())) {
            Item item = result.get();
            if (key.equals(item.objectName())) {
                items.add(item);
            }
        }
        return items;
    }

    private static List<String> versionIds(MinioClient client, String bucket, String key)
            throws Exception {
        return exactVersions(client, bucket, key).stream().map(Item::versionId).toList();
    }

    private record Fixture(UUID actor, UUID base, UUID document) { }

    @FunctionalInterface
    private interface InterruptibleAction {
        void run() throws InterruptedException;
    }
}
