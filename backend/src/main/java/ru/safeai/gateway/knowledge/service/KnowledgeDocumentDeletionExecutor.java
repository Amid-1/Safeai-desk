package ru.safeai.gateway.knowledge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import ru.safeai.gateway.knowledge.storage.VerifiedObjectLockException;
import ru.safeai.gateway.knowledge.rag.KnowledgeRagExecutionPermitService;
import ru.safeai.gateway.knowledge.storage.ErasureTombstoneJournal;

import java.util.concurrent.atomic.AtomicLong;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * По умолчанию отключённый исполнитель безопасного удаления документов.
 * Порядок работы: (1) транзакционно зафиксировать инвентаризацию, (2) удалить
 * все версии объектов вне DB-транзакции, (3) атомарно очистить оперативные
 * данные и зафиксировать DELETED при соблюдении защитных инвариантов.
 *
 * <p>Исторические идентификаторы V80/V57, доказательства V56, предыдущие
 * RAG-ответы и резервные копии подчиняются отдельным политикам хранения.</p>
 *
 * <p>Требуются валидированные Flyway V78, V80, V81, V84 и V85.
 * Не запускать при непроверенной истории миграций.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "safeai.knowledge.deletion",
        name = "executor-enabled",
        havingValue = "true")
public class KnowledgeDocumentDeletionExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(KnowledgeDocumentDeletionExecutor.class);

    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_CLAIMS_PER_POLL = 5;
    private static final int MAX_RETRY_SECONDS = 900;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectStorage storage;
    private final KnowledgeRagExecutionPermitService executionPermits;
    private final ErasureTombstoneJournal tombstoneJournal;

    /**
     * Совместимость со старыми изолированными тестами.
     * Без внешнего журнала удаление завершиться не может: проверка выполняется
     * до первого необратимого обращения к объектному хранилищу.
     */
    public KnowledgeDocumentDeletionExecutor(JdbcTemplate jdbc,
                                             PlatformTransactionManager transactionManager,
                                             ObjectStorage storage) {
        this(jdbc, transactionManager, storage, null, null);
    }

    /**
     * Совместимость с кодом V85, который передаёт защиту RAG, но ещё не передаёт журнал.
     * Отсутствующий журнал не подменяется заглушкой и не ослабляет защиту удаления.
     */
    public KnowledgeDocumentDeletionExecutor(JdbcTemplate jdbc,
                                             PlatformTransactionManager transactionManager,
                                             ObjectStorage storage,
                                             KnowledgeRagExecutionPermitService executionPermits) {
        this(jdbc, transactionManager, storage, executionPermits, null);
    }

    /**
     * Основной конструктор production и интеграционных тестов.
     * Spring обязан передать реализацию ErasureTombstoneJournal; отсутствие bean
     * предотвращает запуск включённого исполнителя в production.
     * В тестах допускается null только для executionPermits: вместо реального
     * журнала обязательно передаётся тестовая реализация.
     */
    @Autowired
    public KnowledgeDocumentDeletionExecutor(JdbcTemplate jdbc,
                                             PlatformTransactionManager transactionManager,
                                             ObjectStorage storage,
                                             KnowledgeRagExecutionPermitService executionPermits,
                                             ErasureTombstoneJournal tombstoneJournal) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                java.util.Objects.requireNonNull(transactionManager, "transactionManager"));
        this.storage = java.util.Objects.requireNonNull(storage, "storage");
        this.executionPermits = executionPermits;
        this.tombstoneJournal = tombstoneJournal;
    }

    private record Claim(UUID id, UUID organizationId, UUID knowledgeBaseId,
                         UUID documentId, UUID requestedByUserId, long expectedVersion,
                         UUID token, int attempts) { }

    private static final class LeaseLostException extends IllegalStateException {
        private LeaseLostException() {
            super("KNOWLEDGE_ERASURE_LEASE_LOST");
        }
    }

    /**
     * Плановый запуск: обрабатывает ограниченный пакет готовых операций.
     * Логика аренды, внешнего журнала и подтверждения удаления не различается
     * между плановым и однократным исполнением.
     */
    @Scheduled(fixedDelayString = "${safeai.knowledge.deletion.poll-delay:PT20S}")
    public void poll() {
        processUpTo(MAX_CLAIMS_PER_POLL);
    }

    /**
     * Выполняет не более одного штатного захвата из очереди.
     *
     * <p>Используется для ограниченного ручного запуска и интеграционных проверок
     * с отдельным хранилищем. Не обходит условия готовности, lease, fencing,
     * внешний журнал или проверки полной физической очистки.</p>
     */
    public void pollOne() {
        processUpTo(1);
    }

    private void processUpTo(int maximumClaims) {
        for (int i = 0; i < maximumClaims; i++) {
            Claim claim = claim();
            if (claim == null) {
                return;
            }

            try {
                // Инвентаризация фиксируется в БД до необратимых операций с хранилищем.
                boolean ready = Boolean.TRUE.equals(
                        transactions.execute(tx -> prepareInventory(claim)));
                if (!ready) {
                    continue;
                }
                requireTombstoneJournal().publishDocumentIntent(
                        claim.id(), claim.organizationId(), claim.knowledgeBaseId(), claim.documentId(),
                        claim.requestedByUserId(), claim.expectedVersion());

                // Читаем инвентаризацию порциями по ключу. Не загружаем
                // неограниченное количество объектов в память JVM.
                String lastKey = null;
                while (true) {
                    List<String> keys = lastKey == null
                            ? jdbc.queryForList("""
                                    SELECT storage_key
                                      FROM public.knowledge_document_erasure_objects_v81
                                     WHERE operation_id=?
                                     ORDER BY storage_key LIMIT 128
                                    """, String.class, claim.id())
                            : jdbc.queryForList("""
                                    SELECT storage_key
                                      FROM public.knowledge_document_erasure_objects_v81
                                     WHERE operation_id=? AND storage_key > ?
                                     ORDER BY storage_key LIMIT 128
                                    """, String.class, claim.id(), lastKey);
                    if (keys.isEmpty()) break;
                    for (String key : keys) {
                        requireRenewedLease(claim);
                        AtomicLong lastHeartbeat = new AtomicLong(System.nanoTime());
                        // Не выполняем сетевые запросы внутри транзакции PostgreSQL.
                        // Хранилище вызывает heartbeat при обходе и удалении версий.
                        requireTombstoneJournal().publishObjectPurgeIntent(
                                claim.id(), claim.organizationId(), claim.knowledgeBaseId(),
                                claim.documentId(), key);
                        storage.purgeAllVersionsVerified(key, () -> {
                            long now = System.nanoTime();
                            long previous = lastHeartbeat.get();
                            if (now - previous >= java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
                                    && lastHeartbeat.compareAndSet(previous, now)) {
                                requireRenewedLease(claim);
                            }
                        });
                        // Внешнее доказательство фиксируется только после повторной
                        // проверки очистки именно этого ключа.
                        requireTombstoneJournal().publishObjectPurged(
                                claim.id(), claim.organizationId(), claim.knowledgeBaseId(),
                                claim.documentId(), key);
                    }
                    lastKey = keys.getLast();
                }

                requireRenewedLease(claim);
                requireTombstoneJournal().publishDocumentCommitted(
                        claim.id(), claim.organizationId(), claim.knowledgeBaseId(), claim.documentId());
                boolean completed = Boolean.TRUE.equals(
                        transactions.execute(tx -> eraseDatabase(claim)));
                if (!completed) {
                    throw new LeaseLostException();
                }
            } catch (LeaseLostException lost) {
                // Истёкшая аренда могла перейти другому исполнителю.
                // Нельзя сбрасывать чужой токен или подтверждать устаревший захват.
                log.warn("Knowledge deletion lease lost: operationId={}", claim.id());
            } catch (Exception error) {
                if (isVerifiedRetentionFailure(error)) {
                    try {
                        requireTombstoneJournal().publishAttention(
                                claim.id(), claim.organizationId(), claim.knowledgeBaseId(),
                                claim.documentId(), "OBJECT_LOCK_VERIFIED");
                    } catch (Exception journalFailure) {
                        error.addSuppressed(journalFailure);
                    }
                }
                fail(claim, error);
            }
        }
    }

    private Claim claim() {
        UUID token = UUID.randomUUID();
        List<Claim> results = jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM public.knowledge_document_deletions_v78
                    WHERE state = 'DELETING'
                      AND next_attempt_at <= clock_timestamp()
                      AND (lease_until IS NULL OR lease_until < clock_timestamp())
                    ORDER BY next_attempt_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE public.knowledge_document_deletions_v78 d
                SET lease_token = ?,
                    lease_until = clock_timestamp() + interval '5 minutes',
                    attempts = d.attempts + 1,
                    updated_at = clock_timestamp()
                FROM candidate
                WHERE d.id = candidate.id
                RETURNING d.id, d.organization_id, d.knowledge_base_id,
                          d.document_id, d.requested_by_user_id, d.expected_version,
                        d.lease_token, d.attempts
                """, (rs, rowNum) -> new Claim(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("knowledge_base_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("requested_by_user_id", UUID.class),
                rs.getLong("expected_version"),
                rs.getObject("lease_token", UUID.class),
                rs.getInt("attempts")), token);

        return results.isEmpty() ? null : results.getFirst();
    }

    private void requireRenewedLease(Claim claim) {
        int updated = jdbc.update("""
                UPDATE public.knowledge_document_deletions_v78
                SET lease_until = clock_timestamp() + interval '5 minutes',
                    updated_at = clock_timestamp()
                WHERE id = ? AND lease_token = ? AND state = 'DELETING'
                  AND lease_until > clock_timestamp()
                """, claim.id(), claim.token());
        if (updated != 1) {
            throw new LeaseLostException();
        }
    }

    private boolean prepareInventory(Claim claim) {
        assertDeletionLeaseUnderDocumentLock(claim);

        // AI-вызов, начатый до DELETING, может ещё выполняться.
        // Новые попытки блокируются V85 через блокировки строк базы/документа.
        if (executionPermits != null && executionPermits.hasActiveAttemptsForDocument(
                claim.organizationId(), claim.knowledgeBaseId(), claim.documentId())) {
            rescheduleForActiveExecution(claim);
            return false;
        }

        // Исход незавершённой загрузки может быть неизвестен.
        // Нельзя считать объект отсутствующим и продолжать неполную очистку.
        boolean uncertainIntent = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.knowledge_storage_upload_intents
                    WHERE organization_id = ? AND knowledge_base_id = ?
                      AND document_id = ?
                      AND state NOT IN ('LINKED', 'CLEANED')
                )
                """, Boolean.class,
                claim.organizationId(), claim.knowledgeBaseId(), claim.documentId()));

        if (uncertainIntent) {
            requireTerminalTransition(claim, "NEEDS_REVIEW", "UNCERTAIN_UPLOAD_INTENT");
            return false;
        }

        // Даже CLEANED требует проверки: обычный S3 DELETE ранее мог оставить
        // старые физические версии в бакете с версионированием.
        jdbc.update("""
                INSERT INTO public.knowledge_document_erasure_objects_v81
                    (operation_id, storage_key)
                SELECT ?, storage_key
                  FROM public.knowledge_document_versions
                 WHERE organization_id = ? AND knowledge_base_id = ? AND document_id = ?
                UNION
                SELECT ?, storage_key
                  FROM public.knowledge_storage_upload_intents
                 WHERE organization_id = ? AND knowledge_base_id = ? AND document_id = ?
                   AND state IN ('LINKED', 'CLEANED')
                ON CONFLICT DO NOTHING
                """, claim.id(), claim.organizationId(), claim.knowledgeBaseId(),
                claim.documentId(), claim.id(), claim.organizationId(),
                claim.knowledgeBaseId(), claim.documentId());

        return true;
    }

    private boolean eraseDatabase(Claim claim) {
        assertDeletionLeaseUnderDocumentLock(claim);

        // При появлении нового ключа после инвентаризации прекращаем удаление.
        boolean missingKey = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT storage_key
                      FROM public.knowledge_document_versions
                     WHERE organization_id = ? AND knowledge_base_id = ? AND document_id = ?
                    UNION
                    SELECT storage_key
                      FROM public.knowledge_storage_upload_intents
                     WHERE organization_id = ? AND knowledge_base_id = ? AND document_id = ?
                       AND state IN ('LINKED', 'CLEANED')
                    EXCEPT
                    SELECT storage_key
                      FROM public.knowledge_document_erasure_objects_v81
                     WHERE operation_id = ?
                )
                """, Boolean.class,
                claim.organizationId(), claim.knowledgeBaseId(), claim.documentId(),
                claim.organizationId(), claim.knowledgeBaseId(), claim.documentId(),
                claim.id()));
        if (missingKey) {
            throw new IllegalStateException("INCOMPLETE_ERASURE_INVENTORY");
        }

        // Настройки сессии ограничены текущей транзакцией и повторно
        // проверяются V81 по активной аренде и статусу DELETING.
        jdbc.queryForObject(
                "SELECT set_config('safeai.knowledge.erasure_operation', ?, true)",
                String.class, claim.id().toString());
        jdbc.queryForObject(
                "SELECT set_config('safeai.knowledge.erasure_token', ?, true)",
                String.class, claim.token().toString());

        int detached = jdbc.update("""
                UPDATE public.knowledge_documents
                   SET current_version_id = NULL,
                       searchable_version_id = NULL,
                       enabled = false
                 WHERE id = ? AND organization_id = ? AND knowledge_base_id = ?
                   AND lifecycle_state = 'DELETING'
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());
        if (detached != 1) {
            throw new IllegalStateException("ERASURE_DOCUMENT_NOT_DELETING");
        }

        // Очищаем пользовательское имя в сохранённом снимке; V80 сохраняет
        // независимые идентификаторы chunks, чтобы не нарушить FK цитат.
        jdbc.update("""
                UPDATE public.knowledge_retrieval_hits h
                   SET document_name_snapshot = 'Удалённый документ'
                  FROM public.knowledge_chunk_identities_v80 ci
                 WHERE h.chunk_id = ci.id
                   AND ci.document_id = ? AND ci.organization_id = ?
                   AND ci.knowledge_base_id = ?
                   AND h.document_name_snapshot <> 'Удалённый документ'
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());

        jdbc.update("""
                DELETE FROM public.knowledge_document_chunks
                 WHERE document_id = ? AND organization_id = ? AND knowledge_base_id = ?
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());

        // V57/V63 требуют переходов состояния GC и сохранения доказательств поколений.
        // Сначала удаляем chunks, соблюдая ограничения GC для цитируемых фрагментов.
        jdbc.update("""
                UPDATE public.knowledge_index_generations
                   SET state = 'RETIRED',
                       retired_at = greatest(clock_timestamp(), published_at)
                 WHERE document_id = ? AND organization_id = ?
                   AND knowledge_base_id = ? AND state = 'ACTIVE'
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());
        jdbc.update("""
                UPDATE public.knowledge_index_generations
                   SET state = 'COLLECTING'
                 WHERE document_id = ? AND organization_id = ?
                   AND knowledge_base_id = ? AND state = 'RETIRED'
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());
        jdbc.update("""
                UPDATE public.knowledge_index_generations
                   SET state = 'COLLECTED',
                       collected_at = greatest(clock_timestamp(), retired_at)
                 WHERE document_id = ? AND organization_id = ?
                   AND knowledge_base_id = ? AND state = 'COLLECTING'
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());

        jdbc.update("""
                DELETE FROM public.knowledge_ingestion_jobs
                 WHERE document_id = ? AND organization_id = ? AND knowledge_base_id = ?
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());
        jdbc.update("""
                DELETE FROM public.knowledge_document_versions
                 WHERE document_id = ? AND organization_id = ? AND knowledge_base_id = ?
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());

        int removed = jdbc.update("""
                DELETE FROM public.knowledge_documents
                 WHERE id = ? AND organization_id = ? AND knowledge_base_id = ?
                   AND lifecycle_state = 'DELETING'
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());
        if (removed != 1) {
            throw new IllegalStateException("ERASURE_DOCUMENT_DELETE_NOT_APPLIED");
        }

        boolean remaining = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.knowledge_documents WHERE id = ?)
                    OR EXISTS (SELECT 1 FROM public.knowledge_document_versions WHERE document_id = ?)
                    OR EXISTS (SELECT 1 FROM public.knowledge_ingestion_jobs WHERE document_id = ?)
                    OR EXISTS (SELECT 1 FROM public.knowledge_document_chunks WHERE document_id = ?)
                """, Boolean.class, claim.documentId(), claim.documentId(),
                claim.documentId(), claim.documentId()));
        if (remaining) {
            throw new IllegalStateException("INCOMPLETE_DB_ERASURE");
        }

        // V81 независимо запрещает DELETED при оставшемся содержимом.
        jdbc.update("""
                DELETE FROM public.knowledge_document_erasure_objects_v81
                 WHERE operation_id = ?
                """, claim.id());
        requireTerminalTransition(claim, "DELETED", null);
        return true;
    }

    /**
     * Бросаем исключение вместо булева результата, который вызывающий код
     * мог бы проигнорировать. Вызывается только внутри DB-транзакции.
     */
    private void assertDeletionLeaseUnderDocumentLock(Claim claim) {
        List<String> states = jdbc.queryForList("""
                SELECT lifecycle_state
                  FROM public.knowledge_documents
                 WHERE id = ? AND organization_id = ? AND knowledge_base_id = ?
                 FOR UPDATE
                """, String.class,
                claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());
        if (states.size() != 1 || !"DELETING".equals(states.getFirst())) {
            throw new IllegalStateException("ERASURE_DOCUMENT_NOT_DELETING");
        }

        boolean leaseOwned = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.knowledge_document_deletions_v78
                     WHERE id = ? AND organization_id = ? AND knowledge_base_id = ?
                       AND document_id = ? AND lease_token = ?
                       AND state = 'DELETING' AND lease_until > clock_timestamp()
                )
                """, Boolean.class, claim.id(), claim.organizationId(),
                claim.knowledgeBaseId(), claim.documentId(), claim.token()));
        if (!leaseOwned) {
            throw new LeaseLostException();
        }
    }

    /**
     * Сохраняем терминальное состояние лишь при подтверждённом владении арендой.
     * Потеря аренды всегда приводит к исключению, а не молчаливому продолжению.
     */
    private void requireTerminalTransition(Claim claim, String state, String errorCode) {
        int updated = jdbc.update("""
                UPDATE public.knowledge_document_deletions_v78
                   SET state = ?, last_error_code = ?,
                       lease_token = NULL, lease_until = NULL,
                       completed_at = CASE WHEN ? = 'DELETED'
                                           THEN clock_timestamp() END,
                       updated_at = clock_timestamp()
                 WHERE id = ? AND lease_token = ? AND state = 'DELETING'
                   AND lease_until > clock_timestamp()
                """, state, errorCode, state, claim.id(), claim.token());
        if (updated != 1) {
            throw new LeaseLostException();
        }
    }

    private void rescheduleForActiveExecution(Claim claim) {
        int updated = jdbc.update("""
                UPDATE public.knowledge_document_deletions_v78
                   SET attempts=GREATEST(0, attempts-1),
                       next_attempt_at=clock_timestamp()+interval '20 seconds',
                       lease_token=NULL, lease_until=NULL,
                       updated_at=clock_timestamp()
                 WHERE id=? AND lease_token=? AND state='DELETING'
                   AND lease_until > clock_timestamp()
                """, claim.id(), claim.token());
        if (updated != 1) throw new LeaseLostException();
    }

    private ErasureTombstoneJournal requireTombstoneJournal() {
        if (tombstoneJournal == null) {
            throw new IllegalStateException("External erasure tombstone journal is required before DELETED");
        }
        return tombstoneJournal;
    }

    private static boolean isVerifiedRetentionFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof VerifiedObjectLockException) return true;
        }
        return false;
    }

    private void fail(Claim claim, Exception error) {
        boolean blockedByRetention = isVerifiedRetentionFailure(error);
        // Retention подтверждается для конкретной версии S3, а не выводится
        // из общей ошибки провайдера, которая может означать недостаток прав.
        String code = blockedByRetention ? "OBJECT_LOCK_VERIFIED"
                : error.getClass().getSimpleName().toUpperCase(Locale.ROOT);

        String newState = blockedByRetention ? "BLOCKED_BY_RETENTION"
                : claim.attempts() >= MAX_ATTEMPTS ? "NEEDS_REVIEW" : "DELETING";
        int retrySeconds = Math.min(MAX_RETRY_SECONDS,
                10 * (1 << Math.min(6, claim.attempts())));

        int updated = jdbc.update("""
                UPDATE public.knowledge_document_deletions_v78
                   SET state = ?, last_error_code = ?,
                       next_attempt_at = clock_timestamp() + (? * interval '1 second'),
                       lease_token = NULL, lease_until = NULL,
                       updated_at = clock_timestamp()
                 WHERE id = ? AND lease_token = ? AND state = 'DELETING'
                   AND lease_until > clock_timestamp()
                """, newState, code.substring(0, Math.min(64, code.length())),
                retrySeconds, claim.id(), claim.token());

        if (updated == 0) {
            log.warn("Knowledge erasure failure on an expired/fenced claim: operationId={} cause={}",
                    claim.id(), code);
        } else {
            log.warn("Knowledge erasure requires retry/review: operationId={} state={} cause={}",
                    claim.id(), newState, code);
        }
    }
}
