package ru.safeai.gateway.knowledge.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentDeletionResponse;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.model.KnowledgeDocumentLifecycleState;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentDeletionRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentRepository;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 2: atomic, durable deletion acceptance. This class NEVER purges content.
 * The HTTP controller stays disabled until the verified purge worker is released.
 */
@Service
public class KnowledgeDocumentDeletionAcceptanceService {
    private final KnowledgeAccessService access;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentDeletionRepository deletions;
    private final AuditEventService audit;

    public KnowledgeDocumentDeletionAcceptanceService(
            KnowledgeAccessService access,
            KnowledgeDocumentRepository documents,
            KnowledgeDocumentDeletionRepository deletions,
            AuditEventService audit) {
        this.access = access;
        this.documents = documents;
        this.deletions = deletions;
        this.audit = audit;
    }

    @Transactional
    public KnowledgeDocumentDeletionResponse accept(UUID knowledgeBaseId, UUID documentId,
                                                     long expectedVersion, String idempotencyKey,
                                                     SafeAiUserPrincipal user) {
        String key = requireKey(idempotencyKey);
        if (expectedVersion < 0) {
            throw new BadRequestException("Версия документа должна быть неотрицательной.");
        }
        // OWNER or same-tenant ADMIN; access is checked in this DB transaction.
        access.requireAccess(knowledgeBaseId, user, KnowledgeBaseAccessLevel.OWNER);
        UUID orgId = user.getOrganizationId();

        var replay = deletions.findByKey(orgId, key);
        if (replay.isPresent()) {
            return requireSameRequest(replay.get(), knowledgeBaseId, documentId,
                    expectedVersion).response();
        }

        // Serialize with upload-version, rename and publication on the document row.
        KnowledgeDocumentEntity document = documents.findForUpdate(
                documentId, knowledgeBaseId, orgId
        ).orElseThrow(() -> new ResourceNotFoundException("Документ не найден."));

        if (document.getLifecycleState() != KnowledgeDocumentLifecycleState.AVAILABLE) {
            var existing = deletions.findByDocument(orgId, documentId);
            if (existing.isPresent()
                    && existing.get().idempotencyKey().equals(key)) {
                return requireSameRequest(existing.get(), knowledgeBaseId, documentId,
                        expectedVersion).response();
            }
            throw new ConflictException("Удаление документа уже выполняется.");
        }
        if (document.getVersion() != expectedVersion) {
            throw new ConflictException("Документ изменился. Обновите список и повторите действие.");
        }

        UUID operationId = UUID.randomUUID();
        if (!deletions.insertIfAbsent(operationId, orgId, knowledgeBaseId,
                documentId, user.getId(), key, expectedVersion)) {
            // Retry by idempotency key after a concurrent INSERT. No document is altered.
            var existing = deletions.findByKey(orgId, key);
            if (existing.isPresent()) {
                return requireSameRequest(existing.get(), knowledgeBaseId, documentId,
                        expectedVersion).response();
            }
            throw new ConflictException("Удаление этого документа уже выполняется.");
        }

        // The DB trigger requires the intent to exist before DELETING is set.
        document.setEnabled(false);
        document.setLifecycleState(KnowledgeDocumentLifecycleState.DELETING);
        documents.saveAndFlush(document);

        audit.recordPrincipalSnapshot(
                user, orgId, AuditEventType.KNOWLEDGE_DOCUMENT_DELETION_REQUESTED,
                () -> Map.of("knowledgeBaseId", knowledgeBaseId.toString(),
                        "documentId", documentId.toString(),
                        "operationId", operationId.toString())
        );
        return deletions.findById(orgId, knowledgeBaseId, operationId)
                .orElseThrow(() -> new IllegalStateException("Deletion intent not persisted"))
                .response();
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentDeletionResponse status(UUID knowledgeBaseId, UUID operationId,
                                                    SafeAiUserPrincipal user) {
        access.requireAccess(knowledgeBaseId, user, KnowledgeBaseAccessLevel.OWNER);
        return deletions.findById(user.getOrganizationId(), knowledgeBaseId, operationId)
                .orElseThrow(() -> new ResourceNotFoundException("Операция удаления не найдена."))
                .response();
    }

    @Transactional(readOnly = true)
    public java.util.List<KnowledgeDocumentDeletionResponse> list(UUID knowledgeBaseId,
                                                                    SafeAiUserPrincipal user) {
        access.requireAccess(knowledgeBaseId, user, KnowledgeBaseAccessLevel.OWNER);
        return deletions.listByBase(user.getOrganizationId(), knowledgeBaseId);
    }

    @Transactional(readOnly = true)
    public boolean capability(UUID knowledgeBaseId, SafeAiUserPrincipal user) {
        access.requireAccess(knowledgeBaseId, user, KnowledgeBaseAccessLevel.OWNER);
        return true;
    }

    private static KnowledgeDocumentDeletionRepository.Request requireSameRequest(
            KnowledgeDocumentDeletionRepository.Request operation, UUID knowledgeBaseId,
            UUID documentId, long expectedVersion) {
        if (!operation.knowledgeBaseId().equals(knowledgeBaseId)
                || !operation.documentId().equals(documentId)
                || operation.expectedVersion() != expectedVersion) {
            throw new ConflictException("Ключ идемпотентности уже использован для другой операции.");
        }
        return operation;
    }

    private static String requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{12,128}")) {
            throw new BadRequestException("Некорректный Idempotency-Key.");
        }
        return key;
    }
}
