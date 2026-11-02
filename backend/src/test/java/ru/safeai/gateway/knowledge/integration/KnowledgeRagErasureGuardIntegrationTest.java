package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.execution.AiExecutionAttemptAuthorization;
import ru.safeai.gateway.ai.execution.AiExecutionIdentity;
import ru.safeai.gateway.ai.execution.AiExecutionRequest;
import ru.safeai.gateway.ai.execution.ProviderExecutionTarget;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;
import ru.safeai.gateway.knowledge.rag.KnowledgeContextSource;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.knowledge.rag.KnowledgeRagContextReceiptService;
import ru.safeai.gateway.knowledge.rag.KnowledgeRagContextRevokedException;
import ru.safeai.gateway.knowledge.rag.KnowledgeRagExecutionPermitService;
import ru.safeai.gateway.knowledge.rag.RagPreparation;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalHit;
import ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Интеграционная проверка согласования RAG-контекста, физических AI-попыток и удаления.
 *
 * <p>Все сценарии используют общий PostgreSQL из Testcontainers. Физическое
 * S3-удаление здесь не запускается: для него предусмотрены отдельные тесты.
 * Реальные feature flags удаления не включаются.</p>
 *
 * <p>Главная гарантия: после перевода базы или документа в DELETING нельзя выдать
 * новое разрешение, начать новую физическую AI-попытку или успешно завершить
 * ответ с отозванным контекстом. При этом уже начатая попытка остаётся учтённой
 * до подтверждённого завершения.</p>
 */
@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class KnowledgeRagErasureGuardIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private KnowledgeRagContextReceiptService receipts;

    @Autowired
    private KnowledgeRagExecutionPermitService executionPermits;

    /**
     * Каждому экземпляру JUnit-теста соответствует новая логическая база знаний.
     * Реестр identities из V82 намеренно не очищается: это неизменяемое
     * доказательство существования базы, а повторное использование UUID запрещено.
     */
    private final UUID knowledgeBaseId = UUID.randomUUID();
    private final UUID chatTurnId = UUID.randomUUID();
    private final UUID providerOperationId = UUID.randomUUID();

    /** Идентификаторы исходного документа также уникальны для каждого теста. */
    private final UUID documentId = UUID.randomUUID();
    private final UUID documentVersionId = UUID.randomUUID();

    @Test
    void documentDeletionInvalidatesMaterializedKnowledgeContext() {
        RagPreparation prepared = createBaseDocumentAndVersion();
        receipts.assertSourcesAvailable(prepared, PLATFORM_ORGANIZATION_ID);

        markDocumentDeleting(prepared.sources().getFirst().hit().documentId());

        // Старый receipt не должен пережить принятие удаления документа.
        assertThatThrownBy(() -> receipts.assertSourcesAvailable(prepared, PLATFORM_ORGANIZATION_ID))
                .isInstanceOf(KnowledgeRagContextRevokedException.class);
    }

    @Test
    void baseDeletionInvalidatesKnowledgeContextIncludingEmptyRetrieval() {
        createBaseDocumentAndVersion();
        RagPreparation emptyContext = prepareForTest(KnowledgeMode.KNOWLEDGE_ASSISTED, 0);
        receipts.assertSourcesAvailable(emptyContext, PLATFORM_ORGANIZATION_ID);

        markBaseDeleting();

        // Даже при нулевом числе retrieval hits разрешение связано с базой.
        // Проверяем именно доменное исключение: подмена через JPA-транслятор
        // на InvalidDataAccessApiUsageException недопустима для AI-границы.
        assertThatThrownBy(() -> receipts.assertSourcesAvailable(emptyContext, PLATFORM_ORGANIZATION_ID))
                .isInstanceOf(KnowledgeRagContextRevokedException.class);
    }

    @Test
    void physicalAttemptLeaseBlocksErasureAndRevokesRetriesAfterDeleteAcceptance() {
        RagPreparation prepared = createBaseDocumentAndVersion();
        UUID documentId = prepared.sources().getFirst().hit().documentId();
        UUID permitId = permitFor(prepared);
        AiExecutionRequest request = executionRequest(permitId);
        AiProviderAttemptContext first = attempt(1, 2);

        UUID leaseId = executionPermits.beforePhysicalAttempt(request, first);
        assertThat(leaseId).isNotNull();
        assertThat(executionPermits.hasActiveAttemptsForDocument(
                PLATFORM_ORGANIZATION_ID, knowledgeBaseId, documentId)).isTrue();

        markDocumentDeleting(documentId);

        // Принятие удаления не освобождает ранее выданный lease автоматически.
        assertThat(executionPermits.hasActiveAttemptsForDocument(
                PLATFORM_ORGANIZATION_ID, knowledgeBaseId, documentId)).isTrue();

        // Новая физическая попытка после принятия удаления запрещена.
        assertThatThrownBy(() -> executionPermits.beforePhysicalAttempt(request, attempt(2, 2)))
                .isInstanceOf(KnowledgeRagContextRevokedException.class);

        // Финальная проверка выполняется в транзакции сохранения ChatTurn.
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> executionPermits.assertFinalizationAllowed(
                        permitId, chatTurnId, PLATFORM_ORGANIZATION_ID)))
                .isInstanceOf(KnowledgeRagContextRevokedException.class);

        executionPermits.afterPhysicalAttempt(
                request, first, leaseId, AiExecutionAttemptAuthorization.Outcome.FAILED);
        assertThat(executionPermits.hasActiveAttemptsForDocument(
                PLATFORM_ORGANIZATION_ID, knowledgeBaseId, documentId)).isFalse();
    }

    @Test
    void emptyRetrievalStillHoldsBaseScopedAttemptLease() {
        createBaseDocumentAndVersion();
        RagPreparation empty = prepareForTest(KnowledgeMode.KNOWLEDGE_ASSISTED, 0);
        UUID permitId = permitFor(empty);
        AiExecutionRequest request = executionRequest(permitId);
        AiProviderAttemptContext first = attempt(1, 1);

        UUID leaseId = executionPermits.beforePhysicalAttempt(request, first);
        assertThat(leaseId).isNotNull();
        assertThat(executionPermits.hasActiveAttemptsForBase(PLATFORM_ORGANIZATION_ID, knowledgeBaseId))
                .isTrue();

        executionPermits.afterPhysicalAttempt(
                request, first, leaseId, AiExecutionAttemptAuthorization.Outcome.SUCCEEDED);
        assertThat(executionPermits.hasActiveAttemptsForBase(PLATFORM_ORGANIZATION_ID, knowledgeBaseId))
                .isFalse();
    }

    @Test
    void newPermitIsDeniedAfterDocumentDeletionAccepted() {
        RagPreparation prepared = createBaseDocumentAndVersion();
        markDocumentDeleting(prepared.sources().getFirst().hit().documentId());

        // Защищаем также путь, когда ещё не создан execution permit.
        assertThatThrownBy(() -> permitFor(prepared))
                .isInstanceOf(KnowledgeRagContextRevokedException.class);
    }

    @Test
    void newPermitIsDeniedAfterBaseDeletionEvenWithoutHits() {
        createBaseDocumentAndVersion();
        RagPreparation empty = prepareForTest(KnowledgeMode.KNOWLEDGE_ASSISTED, 0);
        markBaseDeleting();

        assertThatThrownBy(() -> permitFor(empty))
                .isInstanceOf(KnowledgeRagContextRevokedException.class);
    }

    @Test
    void validPermitCannotBeUsedForAnotherChatTurn() {
        RagPreparation prepared = createBaseDocumentAndVersion();
        UUID permitId = permitFor(prepared);
        UUID foreignTurnId = UUID.randomUUID();
        UUID routeDecisionId = UUID.randomUUID();
        AiChatRequest aiRequest = new AiChatRequest(
                UUID.randomUUID(), PLATFORM_ORGANIZATION_ID, UUID.randomUUID(), providerOperationId,
                "system", "developer", "Question", List.of(), 4096L, 512);
        AiExecutionIdentity identity = mock(AiExecutionIdentity.class);
        when(identity.decisionId()).thenReturn(routeDecisionId);
        when(identity.chatTurnId()).thenReturn(foreignTurnId);
        when(identity.organizationId()).thenReturn(PLATFORM_ORGANIZATION_ID);
        when(identity.knowledgeBaseId()).thenReturn(knowledgeBaseId);
        when(identity.usesKnowledge()).thenReturn(true);
        AiExecutionRequest foreignRequest = new AiExecutionRequest(
                foreignTurnId, routeDecisionId, aiRequest,
                ProviderExecutionTarget.staticTarget("openai", "gpt-5"),
                identity, aiRequest, permitId);

        // Разрешение привязано к исходному ChatTurn и не допускает переноса между диалогами.
        assertThatThrownBy(() -> executionPermits.beforePhysicalAttempt(
                foreignRequest, attempt(1, 1)))
                .isInstanceOf(KnowledgeRagContextRevokedException.class);
        assertThat(executionPermits.hasActiveAttemptsForBase(
                PLATFORM_ORGANIZATION_ID, knowledgeBaseId)).isFalse();
    }

    @Test
    void successfulFinalizationConsumesPermitAndPreventsFurtherPhysicalAttempts() {
        RagPreparation prepared = createBaseDocumentAndVersion();
        UUID permitId = permitFor(prepared);
        AiExecutionRequest request = executionRequest(permitId);
        AiProviderAttemptContext first = attempt(1, 1);
        UUID leaseId = executionPermits.beforePhysicalAttempt(request, first);

        // После подтверждённого завершения физической попытки разрешение можно
        // использовать только для атомарной финализации ответа.
        executionPermits.afterPhysicalAttempt(request, first, leaseId,
                AiExecutionAttemptAuthorization.Outcome.SUCCEEDED);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                executionPermits.assertFinalizationAllowed(
                        permitId, chatTurnId, PLATFORM_ORGANIZATION_ID));

        // Финализированное разрешение нельзя повторно использовать для вызова провайдера.
        assertThatThrownBy(() -> executionPermits.beforePhysicalAttempt(
                request, attempt(1, 1)))
                .isInstanceOf(KnowledgeRagContextRevokedException.class);
        assertThat(executionPermits.hasActiveAttemptsForBase(
                PLATFORM_ORGANIZATION_ID, knowledgeBaseId)).isFalse();
    }

    @Test
    void unknownSourceVersionCannotReceiveExecutionPermit() {
        createBaseDocumentAndVersion();
        var unknownSource = new KnowledgeContextSource(
                "C999", KnowledgeRegressionFixtures.hit(999, "Несуществующий источник"));
        RagPreparation prepared = prepareForTest(KnowledgeMode.KNOWLEDGE_ONLY, 0);

        // Недостаточно доступности базы: каждая конкретная версия обязана существовать.
        assertThatThrownBy(() -> executionPermits.createPermit(
                chatTurnId, PLATFORM_ORGANIZATION_ID, knowledgeBaseId,
                prepared.retrievalRunId(), prepared.contextSha256(), List.of(unknownSource)))
                .isInstanceOf(KnowledgeRagContextRevokedException.class);
    }

    /**
     * Пересобирает эталонную RAG-фикстуру с уникальным набором идентификаторов.
     * Содержимое и параметры поиска остаются прежними, а изолированная БД
     * получает новый документ и его версию без переиспользования V82 identities.
     */
    private RagPreparation prepareForTest(KnowledgeMode mode, int sourceCount) {
        RagPreparation template = KnowledgeRegressionFixtures.preparation(mode, sourceCount);
        List<KnowledgeContextSource> sources = template.sources().stream()
                .map(source -> {
                    KnowledgeRetrievalHit hit = source.hit();
                    KnowledgeRetrievalHit isolatedHit = new KnowledgeRetrievalHit(
                            UUID.randomUUID(), documentId, documentVersionId,
                            hit.documentName(), hit.versionNumber(), hit.chunkOrdinal(),
                            hit.content(), hit.pageFrom(), hit.pageTo(), hit.heading(),
                            hit.fusedScore(), hit.lexicalRank(), hit.semanticRank(),
                            hit.lexicalScore(), hit.cosineSimilarity(), hit.contentSha256());
                    return new KnowledgeContextSource(source.label(), isolatedHit);
                })
                .toList();
        AiChatRequest request = new AiChatRequest(
                UUID.randomUUID(), PLATFORM_ORGANIZATION_ID, UUID.randomUUID(),
                providerOperationId, "system", "developer", "Question",
                List.of(), 4096L, 512);
        return new RagPreparation(
                mode, knowledgeBaseId, UUID.randomUUID(), template.embeddingModel(),
                template.contextSha256(), sources, request);
    }

    private UUID permitFor(RagPreparation prepared) {
        return executionPermits.createPermit(
                chatTurnId, PLATFORM_ORGANIZATION_ID, knowledgeBaseId,
                prepared.retrievalRunId(), prepared.contextSha256(), prepared.sources());
    }

    private AiProviderAttemptContext attempt(int number, int maximum) {
        return new AiProviderAttemptContext(providerOperationId, UUID.randomUUID(), number, maximum);
    }

    private AiExecutionRequest executionRequest(UUID permitId) {
        AiChatRequest aiRequest = new AiChatRequest(
                UUID.randomUUID(), PLATFORM_ORGANIZATION_ID, UUID.randomUUID(), providerOperationId,
                "system", "developer", "Question", List.of(), 4096L, 512);

        UUID routeDecisionId = UUID.randomUUID();
        AiExecutionIdentity identity = mock(AiExecutionIdentity.class);
        when(identity.decisionId()).thenReturn(routeDecisionId);
        when(identity.chatTurnId()).thenReturn(chatTurnId);
        when(identity.organizationId()).thenReturn(PLATFORM_ORGANIZATION_ID);
        when(identity.knowledgeBaseId()).thenReturn(knowledgeBaseId);
        when(identity.usesKnowledge()).thenReturn(true);

        return new AiExecutionRequest(
                chatTurnId, routeDecisionId, aiRequest,
                ProviderExecutionTarget.staticTarget("openai", "gpt-5"),
                identity, aiRequest, permitId);
    }

    private void markDocumentDeleting(UUID documentId) {
        UUID operationId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_document_deletions_v78
                    (id,organization_id,knowledge_base_id,document_id,
                     requested_by_user_id,idempotency_key,expected_version)
                VALUES (?,?,?,?,?,?,0)
                """, operationId, PLATFORM_ORGANIZATION_ID, knowledgeBaseId,
                documentId, UUID.randomUUID(), "guard-document-" + operationId);
        jdbcTemplate.update("""
                UPDATE public.knowledge_documents
                   SET enabled=false, lifecycle_state='DELETING'
                 WHERE id=? AND organization_id=?
                """, documentId, PLATFORM_ORGANIZATION_ID);
    }

    private void markBaseDeleting() {
        UUID operationId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_base_deletions_v82
                    (id,organization_id,knowledge_base_id,
                     requested_by_user_id,expected_version,idempotency_key)
                VALUES (?,?,?,?,0,?)
                """, operationId, PLATFORM_ORGANIZATION_ID, knowledgeBaseId,
                UUID.randomUUID(), "guard-base-" + operationId);
        jdbcTemplate.update("""
                UPDATE public.knowledge_bases
                   SET enabled=false, lifecycle_state='DELETING'
                 WHERE id=? AND organization_id=?
                """, knowledgeBaseId, PLATFORM_ORGANIZATION_ID);
    }

    private RagPreparation createBaseDocumentAndVersion() {
        RagPreparation prepared = prepareForTest(KnowledgeMode.KNOWLEDGE_ONLY, 1);
        KnowledgeRetrievalHit source = prepared.sources().getFirst().hit();
        assertThat(source.documentId()).isEqualTo(documentId);
        assertThat(source.documentVersionId()).isEqualTo(documentVersionId);
        UUID actor = UUID.randomUUID();
        insertUser(actor, PLATFORM_ORGANIZATION_ID,
                "rag-guard-" + actor + "@example.test", true, "ADMIN", Instant.now());

        // INSERT обязан пройти через неизменяемый триггер регистрации V82.
        assertThat(jdbcTemplate.update("""
                INSERT INTO public.knowledge_bases
                    (id,organization_id,name,visibility,enabled,created_by_user_id,version)
                VALUES (?,?,'RAG guard test','ORGANIZATION',true,?,0)
                """, knowledgeBaseId, PLATFORM_ORGANIZATION_ID, actor)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.knowledge_base_identities_v82
                WHERE id=? AND organization_id=?
                """, Integer.class, knowledgeBaseId, PLATFORM_ORGANIZATION_ID))
                .as("Для базы должна существовать единственная неизменяемая V82 identity")
                .isEqualTo(1);
        assertThat(jdbcTemplate.update("""
                INSERT INTO public.knowledge_documents
                    (id,organization_id,knowledge_base_id,name,enabled,created_by_user_id,version)
                VALUES (?,?,?,'Guard source',true,?,0)
                """, source.documentId(), PLATFORM_ORGANIZATION_ID, knowledgeBaseId, actor))
                .isEqualTo(1);
        assertThat(jdbcTemplate.update("""
                INSERT INTO public.knowledge_document_versions
                    (id,organization_id,knowledge_base_id,document_id,version_number,
                     original_filename,media_type,size_bytes,sha256,storage_key,created_by_user_id)
                VALUES (?,?,?,?,1,'guard.pdf','application/pdf',10,?,?,?)
                """, source.documentVersionId(), PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId, source.documentId(), "a".repeat(64),
                "guard/" + UUID.randomUUID(), actor)).isEqualTo(1);
        return prepared;
    }
}
