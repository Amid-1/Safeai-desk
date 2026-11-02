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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.safeai.gateway.knowledge.testsupport.KnowledgeErasureTestJournal;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionAcceptanceService;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionExecutor;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Сквозная проверка физического удаления документа в настоящих PostgreSQL и MinIO.
 *
 * <p>Проверяет принятие заявки, наличие долговечного намерения, инвентаризацию,
 * удаление всех S3-версий и маркера удаления, атомарный переход в DELETED,
 * обязательную запись аудита и сохранность соседнего объекта.</p>
 *
 * <p>Это не проверка Object Lock, юридического удержания или конкурентных читателей:
 * указанные гарантии тестируются отдельно. Признаки недоказанного удаления
 * никогда не принимаются за успешное завершение.</p>
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
                storage.delete(key); // В версионированном бакете создаётся маркер удаления; обе версии остаются.
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

                // В общей тестовой БД могут сохраняться ранее принятые операции удаления.
                // Штатный worker обрабатывает ограниченный пакет по next_attempt_at.
                // Приоритизируем только собственную готовую операцию, не удаляя и не
                // меняя чужие заявки, их аренды и доказательства удаления.
                prioritizeOwnPendingDeletion(accepted.operationId(), doc);

                // Проверяем исходные условия: документ уже закрыт для поиска,
                // но физическое удаление и терминальный аудит ещё не произошли.
                assertThat(operationState(accepted.operationId()).get("state"))
                        .isEqualTo("DELETING");
                assertThat(countErasureInventory(accepted.operationId())).isZero();

                // Настоящий исполнитель использует PostgreSQL lease, fencing,
                // реальную инвентаризацию и реальное S3 purge всех версий.
                // Однократный запуск не касается других операций общей БД.
                new KnowledgeDocumentDeletionExecutor(
                        jdbcTemplate, transactionManager, storage, null,
                        KnowledgeErasureTestJournal.INSTANCE).pollOne();

                // Нельзя заменять состояние вручную или повторять ошибочные purge
                // до видимости успеха. При отказе предъявляем данные операции,
                // чтобы отличить ошибку S3/триггера от пропуска очереди.
                Map<String, Object> finalState = operationState(accepted.operationId());
                assertThat(finalState.get("state"))
                        .as("Документ обязан перейти в DELETED после подтверждённого S3 purge. "
                                + "Состояние операции: " + finalState
                                + "; инвентаризация: "
                                + countErasureInventory(accepted.operationId())
                                + "; оставшиеся S3-версии: " + exact(client, bucket, key).size())
                        .isEqualTo("DELETED");
                assertThat(finalState.get("completed_at"))
                        .as("У завершённой операции обязательна дата завершения")
                        .isNotNull();
                assertThat(finalState.get("lease_token"))
                        .as("Терминальная операция не должна удерживать аренду")
                        .isNull();
                assertThat(finalState.get("lease_until"))
                        .as("Терминальная операция не должна удерживать срок аренды")
                        .isNull();
                assertThat(finalState.get("last_error_code"))
                        .as("Успешное удаление не должно содержать код ошибки")
                        .isNull();
                assertThat(acceptance.status(base, accepted.operationId(), admin).state())
                        .isEqualTo("DELETED");

                // Триггер V84 обязан записать отдельное намерение аудита в той же
                // транзакции, где операция получила терминальное состояние.
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                        FROM public.audit_outbox
                        WHERE organization_id = ?
                          AND event_type = 'KNOWLEDGE_DOCUMENT_DELETION_COMPLETED'
                          AND details ->> 'operationId' = ?
                          AND actor_user_id IS NULL
                        """, Integer.class, PLATFORM_ORGANIZATION_ID,
                        accepted.operationId().toString()))
                        .as("Терминальное удаление обязано создать системное событие аудита")
                        .isEqualTo(1);
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

    /**
     * Переносит только выбранную новую заявку в начало очереди штатного worker.
     *
     * <p>Ни одна чужая заявка и ни одно поле аренды не меняются. Мы не подменяем
     * механизм claim: он продолжает проверять состояние, готовность и lease.
     * Запрос выполняется после фиксации принятия заявки отдельной транзакцией.</p>
     */
    private void prioritizeOwnPendingDeletion(UUID operationId, UUID documentId) {
        int updated = jdbcTemplate.update("""
                UPDATE public.knowledge_document_deletions_v78 AS target
                   SET next_attempt_at = LEAST(
                           clock_timestamp() - INTERVAL '1 day',
                           COALESCE((
                               SELECT MIN(other.next_attempt_at) - INTERVAL '1 day'
                                 FROM public.knowledge_document_deletions_v78 AS other
                                WHERE other.id <> ?
                                  AND other.state = 'DELETING'
                                  AND (other.lease_until IS NULL
                                       OR other.lease_until < clock_timestamp())
                           ), clock_timestamp() - INTERVAL '1 day')
                       ),
                       updated_at = clock_timestamp()
                 WHERE target.id = ?
                   AND target.document_id = ?
                   AND target.state = 'DELETING'
                   AND target.attempts = 0
                   AND target.lease_token IS NULL
                   AND target.lease_until IS NULL
                """, operationId, operationId, documentId);
        assertThat(updated)
                .as("Приоритет можно назначить только своей новой операции без аренды")
                .isEqualTo(1);
    }

    /** Читает состояние заявки без его изменения и без предположений о порядке очереди. */
    private Map<String, Object> operationState(UUID operationId) {
        return jdbcTemplate.queryForMap("""
                SELECT id, document_id, state, attempts, next_attempt_at,
                       lease_token, lease_until, last_error_code, completed_at
                  FROM public.knowledge_document_deletions_v78
                 WHERE id = ?
                """, operationId);
    }

    /** Считает доказательства инвентаризации указанной операции. */
    private int countErasureInventory(UUID operationId) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM public.knowledge_document_erasure_objects_v81
                 WHERE operation_id = ?
                """, Integer.class, operationId);
        return java.util.Objects.requireNonNull(value,
                "PostgreSQL не вернул количество ключей инвентаризации");
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
