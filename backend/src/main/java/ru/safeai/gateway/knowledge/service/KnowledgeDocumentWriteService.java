package ru.safeai.gateway.knowledge.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.persistence.DatabaseConstraintClassifier;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentResponse;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeDocumentRequest;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentVersionEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeIngestionJobEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentVersionRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeIngestionJobRepository;

import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeDocumentWriteService {

    private static final String DOCUMENT_NAME_UNIQUE =
            "ux_knowledge_documents_kb_name_lower";

    private static final String DOCUMENT_VERSION_UNIQUE =
            "uq_knowledge_document_versions_doc_number";

    private final KnowledgeAccessService accessService;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentVersionRepository versions;
    private final KnowledgeIngestionJobRepository jobs;
    private final AuditEventService audit;

    public KnowledgeDocumentWriteService(
            KnowledgeAccessService accessService,
            KnowledgeDocumentRepository documents,
            KnowledgeDocumentVersionRepository versions,
            KnowledgeIngestionJobRepository jobs,
            AuditEventService audit
    ) {
        this.accessService = accessService;
        this.documents = documents;
        this.versions = versions;
        this.jobs = jobs;
        this.audit = audit;
    }

    @Transactional
    public KnowledgeDocumentResponse createInitialVersion(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId,
            String documentName,
            KnowledgeDocumentFileValidator.ValidatedUpload upload,
            String storageKey,
            SafeAiUserPrincipal user
    ) {
        accessService.requireAccess(
                knowledgeBaseId,
                user,
                KnowledgeBaseAccessLevel.EDITOR
        );

        KnowledgeDocumentEntity document =
                new KnowledgeDocumentEntity();

        document.setId(documentId);
        document.setOrganizationId(user.getOrganizationId());
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setName(documentName);
        document.setEnabled(true);
        document.setCreatedByUserId(user.getId());

        try {
            documents.saveAndFlush(document);
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseConstraintClassifier.isUniqueViolation(
                    exception,
                    DOCUMENT_NAME_UNIQUE
            )) {
                throw duplicateDocumentName();
            }

            throw exception;
        }

        /*
         * CREATED идёт раньше VERSION_UPLOADED.
         * Оба durable audit intent живут в этой же DB transaction,
         * поэтому при любой дальнейшей ошибке оба откатятся.
         */
        audit.record(
                user,
                user.getOrganizationId(),
                AuditEventType.KNOWLEDGE_DOCUMENT_CREATED,
                Map.of(
                        "knowledgeBaseId",
                        knowledgeBaseId.toString(),
                        "documentId",
                        documentId.toString(),
                        "name",
                        documentName
                )
        );

        KnowledgeDocumentVersionEntity version =
                createVersion(
                        document,
                        versionId,
                        1,
                        upload,
                        storageKey,
                        user
                );

        KnowledgeIngestionJobEntity job =
                createIngestionJob(
                        document,
                        versionId,
                        user
                );

        document.setCurrentVersionId(
                versionId
        );
        documents.saveAndFlush(
                document
        );

        recordVersionUploaded(
                document,
                version,
                user
        );

        return KnowledgeDocumentResponse.from(
                document,
                version,
                job.getStatus()
        );
    }

    @Transactional
    public KnowledgeDocumentResponse addVersion(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId,
            KnowledgeDocumentFileValidator.ValidatedUpload upload,
            String storageKey,
            SafeAiUserPrincipal user
    ) {
        accessService.requireAccess(
                knowledgeBaseId,
                user,
                KnowledgeBaseAccessLevel.EDITOR
        );

        /*
         * Row lock сериализует выделение versionNumber для одного document.
         * DB UNIQUE остаётся последней линией защиты.
         */
        KnowledgeDocumentEntity document =
                documents.findForUpdate(
                        documentId,
                        knowledgeBaseId,
                        user.getOrganizationId()
                )
                .orElseThrow(
                        () -> new ru.safeai.gateway.common.exception
                                .ResourceNotFoundException(
                                "Документ не найден."
                        )
                );

        int versionNumber =
                documents.currentVersionNumber(
                        documentId,
                        knowledgeBaseId,
                        user.getOrganizationId()
                ) + 1;

        final KnowledgeDocumentVersionEntity version;

        try {
            version =
                    createVersion(
                            document,
                            versionId,
                            versionNumber,
                            upload,
                            storageKey,
                            user
                    );
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseConstraintClassifier.isUniqueViolation(
                    exception,
                    DOCUMENT_VERSION_UNIQUE
            )) {
                throw new ConflictException(
                        "Номер версии документа уже занят."
                );
            }

            throw exception;
        }

        KnowledgeIngestionJobEntity job =
                createIngestionJob(
                        document,
                        versionId,
                        user
                );

        document.setCurrentVersionId(
                versionId
        );
        documents.saveAndFlush(
                document
        );

        recordVersionUploaded(
                document,
                version,
                user
        );

        return KnowledgeDocumentResponse.from(
                document,
                version,
                job.getStatus()
        );
    }


    @Transactional
    public KnowledgeDocumentResponse updateDocument(
            UUID knowledgeBaseId,
            UUID documentId,
            UpdateKnowledgeDocumentRequest request,
            SafeAiUserPrincipal user
    ) {
        accessService.requireAccess(
                knowledgeBaseId,
                user,
                KnowledgeBaseAccessLevel.EDITOR
        );

        KnowledgeDocumentEntity document =
                documents.findForUpdate(
                        documentId,
                        knowledgeBaseId,
                        user.getOrganizationId()
                )
                .orElseThrow(
                        () -> new ru.safeai.gateway.common.exception
                                .ResourceNotFoundException(
                                "Документ не найден."
                        )
                );

        if (request == null
                || request.expectedVersion() == null
                || request.expectedVersion() < 0L) {
            throw new ru.safeai.gateway.common.exception.BadRequestException(
                    "expectedVersion должен быть указан и быть неотрицательным."
            );
        }

        if (document.getVersion()
                != request.expectedVersion()) {
            throw new ConflictException(
                    "Документ уже изменён другим запросом: expectedVersion="
                            + request.expectedVersion()
                            + ", actualVersion="
                            + document.getVersion()
            );
        }

        String name =
                KnowledgeDocumentNameNormalizer.normalize(
                        request.name()
                );

        boolean enabled =
                Boolean.TRUE.equals(
                        request.enabled()
                );

        if (!document.getName().equalsIgnoreCase(name)
                && documents
                .existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCaseAndIdNot(
                        knowledgeBaseId,
                        user.getOrganizationId(),
                        name,
                        documentId
                )) {
            throw duplicateDocumentName();
        }

        String oldName =
                document.getName();

        boolean oldEnabled =
                document.isEnabled();

        if (oldName.equals(name)
                && oldEnabled == enabled) {
            return responseFor(
                    document,
                    user
            );
        }

        document.setName(name);
        document.setEnabled(enabled);

        try {
            documents.saveAndFlush(
                    document
            );
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseConstraintClassifier.isUniqueViolation(
                    exception,
                    DOCUMENT_NAME_UNIQUE
            )) {
                throw duplicateDocumentName();
            }

            throw exception;
        }

        audit.record(
                user,
                user.getOrganizationId(),
                AuditEventType.KNOWLEDGE_DOCUMENT_UPDATED,
                Map.of(
                        "knowledgeBaseId",
                        knowledgeBaseId.toString(),
                        "documentId",
                        documentId.toString(),
                        "oldName",
                        oldName,
                        "newName",
                        document.getName(),
                        "oldEnabled",
                        oldEnabled,
                        "newEnabled",
                        document.isEnabled()
                )
        );

        return responseFor(
                document,
                user
        );
    }

    private KnowledgeDocumentResponse responseFor(
            KnowledgeDocumentEntity document,
            SafeAiUserPrincipal user
    ) {
        KnowledgeDocumentVersionEntity version =
                document.getCurrentVersionId() == null
                        ? null
                        : versions
                        .findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                                document.getCurrentVersionId(),
                                document.getId(),
                                document.getKnowledgeBaseId(),
                                user.getOrganizationId()
                        )
                        .orElse(null);

        KnowledgeIngestionStatus status =
                version == null
                        ? null
                        : jobs
                        .findByDocumentVersionIdAndOrganizationId(
                                version.getId(),
                                user.getOrganizationId()
                        )
                        .map(
                                KnowledgeIngestionJobEntity::getStatus
                        )
                        .orElse(null);

        return KnowledgeDocumentResponse.from(
                document,
                version,
                status
        );
    }

    private KnowledgeDocumentVersionEntity createVersion(
            KnowledgeDocumentEntity document,
            UUID versionId,
            int versionNumber,
            KnowledgeDocumentFileValidator.ValidatedUpload upload,
            String storageKey,
            SafeAiUserPrincipal user
    ) {
        KnowledgeDocumentVersionEntity version =
                new KnowledgeDocumentVersionEntity();

        version.setId(versionId);
        version.setOrganizationId(
                user.getOrganizationId()
        );
        version.setKnowledgeBaseId(
                document.getKnowledgeBaseId()
        );
        version.setDocumentId(
                document.getId()
        );
        version.setVersionNumber(
                versionNumber
        );
        version.setOriginalFilename(
                upload.originalFilename()
        );
        version.setMediaType(
                upload.mediaType()
        );
        version.setSizeBytes(
                upload.sizeBytes()
        );
        version.setSha256(
                upload.sha256()
        );
        version.setStorageKey(
                storageKey
        );
        version.setCreatedByUserId(
                user.getId()
        );

        return versions.saveAndFlush(
                version
        );
    }

    private KnowledgeIngestionJobEntity createIngestionJob(
            KnowledgeDocumentEntity document,
            UUID documentVersionId,
            SafeAiUserPrincipal user
    ) {
        KnowledgeIngestionJobEntity job =
                new KnowledgeIngestionJobEntity();

        job.setOrganizationId(
                user.getOrganizationId()
        );
        job.setKnowledgeBaseId(
                document.getKnowledgeBaseId()
        );
        job.setDocumentId(
                document.getId()
        );
        job.setDocumentVersionId(
                documentVersionId
        );
        job.setStatus(
                KnowledgeIngestionStatus.PENDING
        );

        return jobs.saveAndFlush(
                job
        );
    }

    private void recordVersionUploaded(
            KnowledgeDocumentEntity document,
            KnowledgeDocumentVersionEntity version,
            SafeAiUserPrincipal user
    ) {
        audit.record(
                user,
                user.getOrganizationId(),
                AuditEventType.KNOWLEDGE_DOCUMENT_VERSION_UPLOADED,
                Map.of(
                        "knowledgeBaseId",
                        document.getKnowledgeBaseId().toString(),
                        "documentId",
                        document.getId().toString(),
                        "documentVersionId",
                        version.getId().toString(),
                        "versionNumber",
                        version.getVersionNumber(),
                        "sha256",
                        version.getSha256()
                )
        );
    }

    private ConflictException duplicateDocumentName() {
        return new ConflictException(
                "Документ с таким названием уже существует."
        );
    }
}
