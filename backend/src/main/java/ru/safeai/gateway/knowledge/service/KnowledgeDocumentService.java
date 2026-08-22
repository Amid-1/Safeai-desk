package ru.safeai.gateway.knowledge.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.persistence.DatabaseConstraintClassifier;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentPageResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentResponse;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseMembershipEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentVersionEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeIngestionJobEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.knowledge.repository.KnowledgeBaseMembershipRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeBaseRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentVersionRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeIngestionJobRepository;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import ru.safeai.gateway.knowledge.storage.StoredObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    private static final String DOCUMENT_NAME_UNIQUE =
            "ux_knowledge_documents_kb_name_lower";

    private final KnowledgeBaseRepository bases;
    private final KnowledgeBaseMembershipRepository memberships;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentVersionRepository versions;
    private final KnowledgeIngestionJobRepository jobs;
    private final ObjectStorage storage;
    private final KnowledgeDocumentFileValidator fileValidator;
    private final AuditEventService audit;

    @Transactional(readOnly = true)
    public KnowledgeDocumentPageResponse list(
            UUID knowledgeBaseId,
            SafeAiUserPrincipal user,
            int page,
            int size
    ) {
        authorize(
                knowledgeBaseId,
                user,
                KnowledgeBaseAccessLevel.VIEWER
        );

        Page<KnowledgeDocumentEntity> result =
                documents.findAllByKnowledgeBaseIdAndOrganizationId(
                        knowledgeBaseId,
                        user.getOrganizationId(),
                        PageRequest.of(
                                page,
                                Math.min(size, 100),
                                Sort.by(
                                        Sort.Direction.DESC,
                                        "updatedAt"
                                )
                        )
                );

        List<KnowledgeDocumentResponse> content = result.getContent()
                .stream()
                .map(this::response)
                .toList();

        return new KnowledgeDocumentPageResponse(
                content,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Transactional
    public KnowledgeDocumentResponse uploadNew(
            UUID knowledgeBaseId,
            String requestedName,
            MultipartFile file,
            SafeAiUserPrincipal user
    ) {
        authorize(
                knowledgeBaseId,
                user,
                KnowledgeBaseAccessLevel.EDITOR
        );

        KnowledgeDocumentFileValidator.ValidatedUpload upload =
                fileValidator.validate(file);

        String name = normalizeName(
                requestedName == null || requestedName.isBlank()
                        ? upload.originalFilename()
                        : requestedName
        );

        if (documents
                .existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(
                        knowledgeBaseId,
                        user.getOrganizationId(),
                        name
                )) {
            throw duplicateDocumentName();
        }

        KnowledgeDocumentEntity document =
                new KnowledgeDocumentEntity();

        document.setOrganizationId(user.getOrganizationId());
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setName(name);
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

        KnowledgeDocumentResponse result =
                storeVersion(document, upload, user);

        audit.record(
                user,
                user.getOrganizationId(),
                AuditEventType.KNOWLEDGE_DOCUMENT_CREATED,
                Map.of(
                        "knowledgeBaseId",
                        knowledgeBaseId.toString(),
                        "documentId",
                        document.getId().toString(),
                        "name",
                        name
                )
        );

        return result;
    }

    @Transactional
    public KnowledgeDocumentResponse uploadVersion(
            UUID knowledgeBaseId,
            UUID documentId,
            MultipartFile file,
            SafeAiUserPrincipal user
    ) {
        authorize(
                knowledgeBaseId,
                user,
                KnowledgeBaseAccessLevel.EDITOR
        );

        KnowledgeDocumentFileValidator.ValidatedUpload upload =
                fileValidator.validate(file);

        KnowledgeDocumentEntity document =
                requireDocumentForUpdate(
                        knowledgeBaseId,
                        documentId,
                        user.getOrganizationId()
                );

        return storeVersion(document, upload, user);
    }

    @Transactional(readOnly = true)
    public Download download(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId,
            SafeAiUserPrincipal user
    ) {
        authorize(
                knowledgeBaseId,
                user,
                KnowledgeBaseAccessLevel.VIEWER
        );

        KnowledgeDocumentEntity document = requireDocument(
                knowledgeBaseId,
                documentId,
                user.getOrganizationId()
        );

        if (!document.isEnabled() && !isAdmin(user)) {
            throw new ResourceNotFoundException(
                    "Документ не найден."
            );
        }

        UUID selectedVersionId = versionId == null
                ? document.getCurrentVersionId()
                : versionId;

        KnowledgeDocumentVersionEntity version =
                versions.findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                        selectedVersionId,
                        documentId,
                        knowledgeBaseId,
                        user.getOrganizationId()
                ).orElseThrow(() -> new ResourceNotFoundException(
                        "Версия документа не найдена."
                ));

        try {
            return new Download(
                    storage.get(version.getStorageKey()),
                    version.getOriginalFilename(),
                    version.getMediaType()
            );
        } catch (IOException exception) {
            throw new ResourceNotFoundException(
                    "Файл документа недоступен."
            );
        }
    }

    private KnowledgeDocumentResponse storeVersion(
            KnowledgeDocumentEntity document,
            KnowledgeDocumentFileValidator.ValidatedUpload upload,
            SafeAiUserPrincipal user
    ) {
        byte[] bytes = upload.bytes();

        int versionNumber = documents.currentVersionNumber(
                document.getId(),
                user.getOrganizationId()
        ) + 1;

        UUID documentVersionId = UUID.randomUUID();

        String storageKey = user.getOrganizationId()
                + "/"
                + document.getKnowledgeBaseId()
                + "/"
                + document.getId()
                + "/"
                + documentVersionId;

        try {
            storage.put(
                    storageKey,
                    new ByteArrayInputStream(bytes)
            );
        } catch (IOException exception) {
            throw new BadRequestException(
                    "Не удалось сохранить файл.",
                    exception
            );
        }

        try {
            KnowledgeDocumentVersionEntity version =
                    new KnowledgeDocumentVersionEntity();

            version.setId(documentVersionId);
            version.setOrganizationId(user.getOrganizationId());
            version.setKnowledgeBaseId(document.getKnowledgeBaseId());
            version.setDocumentId(document.getId());
            version.setVersionNumber(versionNumber);
            version.setOriginalFilename(upload.originalFilename());
            version.setMediaType(upload.mediaType());
            version.setSizeBytes((long) bytes.length);
            version.setSha256(upload.sha256());
            version.setStorageKey(storageKey);
            version.setCreatedByUserId(user.getId());

            versions.saveAndFlush(version);

            KnowledgeIngestionJobEntity job =
                    new KnowledgeIngestionJobEntity();

            job.setOrganizationId(user.getOrganizationId());
            job.setKnowledgeBaseId(document.getKnowledgeBaseId());
            job.setDocumentId(document.getId());
            job.setDocumentVersionId(documentVersionId);
            job.setStatus(KnowledgeIngestionStatus.PENDING);

            jobs.saveAndFlush(job);

            document.setCurrentVersionId(documentVersionId);
            documents.saveAndFlush(document);

            audit.record(
                    user,
                    user.getOrganizationId(),
                    AuditEventType.KNOWLEDGE_DOCUMENT_VERSION_UPLOADED,
                    Map.of(
                            "documentId",
                            document.getId().toString(),
                            "documentVersionId",
                            documentVersionId.toString(),
                            "versionNumber",
                            versionNumber,
                            "sha256",
                            version.getSha256()
                    )
            );

            return KnowledgeDocumentResponse.from(
                    document,
                    version,
                    job.getStatus()
            );
        } catch (RuntimeException exception) {
            try {
                storage.delete(storageKey);
            } catch (IOException ignored) {
                // Reconciliation removes a possible orphan later.
            }

            throw exception;
        }
    }

    private KnowledgeDocumentResponse response(
            KnowledgeDocumentEntity document
    ) {
        KnowledgeDocumentVersionEntity version =
                document.getCurrentVersionId() == null
                        ? null
                        : versions.findById(
                                document.getCurrentVersionId()
                        ).orElse(null);

        KnowledgeIngestionStatus status = version == null
                ? null
                : jobs.findByDocumentVersionIdAndOrganizationId(
                        version.getId(),
                        document.getOrganizationId()
                ).map(KnowledgeIngestionJobEntity::getStatus)
                        .orElse(null);

        return KnowledgeDocumentResponse.from(
                document,
                version,
                status
        );
    }

    private void authorize(
            UUID knowledgeBaseId,
            SafeAiUserPrincipal user,
            KnowledgeBaseAccessLevel required
    ) {
        KnowledgeBaseEntity knowledgeBase =
                bases.findByIdAndOrganizationId(
                        knowledgeBaseId,
                        user.getOrganizationId()
                ).orElseThrow(() -> new ResourceNotFoundException(
                        "База знаний не найдена."
                ));

        if (isAdmin(user)) {
            return;
        }

        if (!knowledgeBase.isEnabled()) {
            throw new ResourceNotFoundException(
                    "База знаний не найдена."
            );
        }

        KnowledgeBaseAccessLevel actual = memberships
                .findByKnowledgeBaseIdAndOrganizationIdAndUserId(
                        knowledgeBaseId,
                        user.getOrganizationId(),
                        user.getId()
                )
                .map(KnowledgeBaseMembershipEntity::getAccessLevel)
                .orElse(
                        knowledgeBase.getVisibility()
                                == KnowledgeBaseVisibility.ORGANIZATION
                                ? KnowledgeBaseAccessLevel.VIEWER
                                : null
                );

        if (actual == null) {
            if (knowledgeBase.getVisibility()
                    == KnowledgeBaseVisibility.MEMBERS) {
                throw new ResourceNotFoundException(
                        "База знаний не найдена."
                );
            }

            throw new ForbiddenOperationException(
                    "Недостаточно прав для операции с документом."
            );
        }

        if (rank(actual) < rank(required)) {
            throw new ForbiddenOperationException(
                    "Недостаточно прав для операции с документом."
            );
        }
    }

    private static int rank(KnowledgeBaseAccessLevel level) {
        return switch (level) {
            case VIEWER -> 1;
            case EDITOR -> 2;
            case OWNER -> 3;
        };
    }

    private static boolean isAdmin(SafeAiUserPrincipal user) {
        return user.getAuthorities()
                .stream()
                .anyMatch(authority -> Objects.equals(
                        "ROLE_ADMIN",
                        authority.getAuthority()
                ));
    }

    private KnowledgeDocumentEntity requireDocument(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID organizationId
    ) {
        return documents.findByIdAndKnowledgeBaseIdAndOrganizationId(
                documentId,
                knowledgeBaseId,
                organizationId
        ).orElseThrow(() -> new ResourceNotFoundException(
                "Документ не найден."
        ));
    }

    private KnowledgeDocumentEntity requireDocumentForUpdate(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID organizationId
    ) {
        return documents.findForUpdate(
                documentId,
                knowledgeBaseId,
                organizationId
        ).orElseThrow(() -> new ResourceNotFoundException(
                "Документ не найден."
        ));
    }

    private static String normalizeName(String value) {
        if (value == null) {
            throw new BadRequestException(
                    "Введите название документа."
            );
        }

        String normalized = value.strip();

        if (normalized.isEmpty()
                || normalized.length() > 255
                || normalized.chars().anyMatch(
                        Character::isISOControl
                )) {
            throw new BadRequestException(
                    "Некорректное название документа."
            );
        }

        return normalized;
    }

    private ConflictException duplicateDocumentName() {
        return new ConflictException(
                "Документ с таким названием уже существует."
        );
    }

    public record Download(
            StoredObject object,
            String filename,
            String mediaType
    ) {
    }
}
