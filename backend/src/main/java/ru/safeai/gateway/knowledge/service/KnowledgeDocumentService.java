package ru.safeai.gateway.knowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
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
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    private static final int MAX_PAGE_SIZE = 100;

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
            UUID kbId,
            SafeAiUserPrincipal user,
            int page,
            int size
    ) {
        KnowledgeBaseAccessLevel access = authorize(
                kbId,
                user,
                KnowledgeBaseAccessLevel.VIEWER
        );

        validatePage(page, size);

        PageRequest request = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("updatedAt"),
                        Sort.Order.desc("id")
                )
        );

        Page<KnowledgeDocumentEntity> result =
                isAdmin(user)
                        || access == KnowledgeBaseAccessLevel.OWNER
                        ? documents.findAllByKnowledgeBaseIdAndOrganizationId(
                                kbId,
                                user.getOrganizationId(),
                                request
                        )
                        : documents.findAllByKnowledgeBaseIdAndOrganizationIdAndEnabledTrue(
                                kbId,
                                user.getOrganizationId(),
                                request
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
            UUID kbId,
            String requestedName,
            MultipartFile file,
            SafeAiUserPrincipal user
    ) {
        authorize(
                kbId,
                user,
                KnowledgeBaseAccessLevel.EDITOR
        );

        KnowledgeDocumentFileValidator.ValidatedUpload upload = fileValidator.validate(file);

        String name = KnowledgeDocumentNameNormalizer.normalize(
                requestedName == null || requestedName.isBlank()
                        ? upload.originalFilename()
                        : requestedName
        );

        if (documents.existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(
                kbId,
                user.getOrganizationId(),
                name
        )) {
            throw new ConflictException(
                    "Документ с таким названием уже существует."
            );
        }

        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setOrganizationId(user.getOrganizationId());
        document.setKnowledgeBaseId(kbId);
        document.setName(name);
        document.setEnabled(true);
        document.setCreatedByUserId(user.getId());

        documents.saveAndFlush(document);

        KnowledgeDocumentResponse result = storeVersion(
                document,
                upload,
                user
        );

        audit.record(
                user,
                user.getOrganizationId(),
                AuditEventType.KNOWLEDGE_DOCUMENT_CREATED,
                Map.of(
                        "knowledgeBaseId", kbId.toString(),
                        "documentId", document.getId().toString(),
                        "name", name
                )
        );

        return result;
    }

    @Transactional
    public KnowledgeDocumentResponse uploadVersion(
            UUID kbId,
            UUID documentId,
            MultipartFile file,
            SafeAiUserPrincipal user
    ) {
        authorize(
                kbId,
                user,
                KnowledgeBaseAccessLevel.EDITOR
        );

        KnowledgeDocumentFileValidator.ValidatedUpload upload = fileValidator.validate(file);

        KnowledgeDocumentEntity document = documents.findForUpdate(
                        documentId,
                        kbId,
                        user.getOrganizationId()
                )
                .orElseThrow(
                        () -> new ResourceNotFoundException(
                                "Документ не найден."
                        )
                );

        return storeVersion(
                document,
                upload,
                user
        );
    }

    @Transactional
    public Download download(
            UUID kbId,
            UUID documentId,
            UUID versionId,
            SafeAiUserPrincipal user
    ) {
        authorize(
                kbId,
                user,
                KnowledgeBaseAccessLevel.VIEWER
        );

        KnowledgeDocumentEntity document = requireDocument(
                kbId,
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

        if (selectedVersionId == null) {
            throw new ResourceNotFoundException(
                    "Версия документа не найдена."
            );
        }

        KnowledgeDocumentVersionEntity version = versions
                .findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                        selectedVersionId,
                        documentId,
                        kbId,
                        user.getOrganizationId()
                )
                .orElseThrow(
                        () -> new ResourceNotFoundException(
                                "Версия документа не найдена."
                        )
                );

        final StoredObject object;
        try {
            object = storage.get(version.getStorageKey());
        } catch (NoSuchFileException exception) {
            throw new ResourceNotFoundException(
                    "Файл документа отсутствует в объектном хранилище."
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Объектное хранилище временно недоступно.",
                    exception
            );
        }

        try {
            audit.record(
                    user,
                    user.getOrganizationId(),
                    AuditEventType.KNOWLEDGE_DOCUMENT_DOWNLOADED,
                    Map.of(
                            "knowledgeBaseId", kbId.toString(),
                            "documentId", documentId.toString(),
                            "documentVersionId", version.getId().toString()
                    )
            );
        } catch (RuntimeException exception) {
            closeQuietly(object);
            throw exception;
        }

        return new Download(
                object,
                version.getOriginalFilename(),
                version.getMediaType()
        );
    }

    private KnowledgeDocumentResponse storeVersion(
            KnowledgeDocumentEntity document,
            KnowledgeDocumentFileValidator.ValidatedUpload upload,
            SafeAiUserPrincipal user
    ) {
        int versionNumber = documents.currentVersionNumber(
                document.getId(),
                user.getOrganizationId()
        ) + 1;

        UUID versionId = UUID.randomUUID();

        String storageKey = user.getOrganizationId()
                + "/"
                + document.getKnowledgeBaseId()
                + "/"
                + document.getId()
                + "/"
                + versionId;

        byte[] bytes = upload.bytes();

        try {
            storage.put(
                    storageKey,
                    new ByteArrayInputStream(bytes)
            );
        } catch (IOException exception) {
            throw new BadRequestException(
                    "Не удалось сохранить файл в объектном хранилище.",
                    exception
            );
        }

        registerRollbackCleanup(storageKey);

        KnowledgeDocumentVersionEntity version =
                new KnowledgeDocumentVersionEntity();

        version.setId(versionId);
        version.setOrganizationId(user.getOrganizationId());
        version.setKnowledgeBaseId(document.getKnowledgeBaseId());
        version.setDocumentId(document.getId());
        version.setVersionNumber(versionNumber);
        version.setOriginalFilename(upload.originalFilename());
        version.setMediaType(upload.mediaType());
        version.setSizeBytes(bytes.length);
        version.setSha256(upload.sha256());
        version.setStorageKey(storageKey);
        version.setCreatedByUserId(user.getId());

        versions.saveAndFlush(version);

        KnowledgeIngestionJobEntity job = createIngestionJob(
                document,
                versionId,
                user
        );

        document.setCurrentVersionId(versionId);
        documents.saveAndFlush(document);

        audit.record(
                user,
                user.getOrganizationId(),
                AuditEventType.KNOWLEDGE_DOCUMENT_VERSION_UPLOADED,
                Map.of(
                        "documentId", document.getId().toString(),
                        "documentVersionId", versionId.toString(),
                        "versionNumber", versionNumber,
                        "sha256", version.getSha256()
                )
        );

        return KnowledgeDocumentResponse.from(
                document,
                version,
                job.getStatus()
        );
    }

    private KnowledgeIngestionJobEntity createIngestionJob(
            KnowledgeDocumentEntity document,
            UUID documentVersionId,
            SafeAiUserPrincipal user
    ) {
        KnowledgeIngestionJobEntity job =
                new KnowledgeIngestionJobEntity();

        job.setOrganizationId(user.getOrganizationId());
        job.setKnowledgeBaseId(document.getKnowledgeBaseId());
        job.setDocumentId(document.getId());
        job.setDocumentVersionId(documentVersionId);
        job.setStatus(KnowledgeIngestionStatus.PENDING);

        return jobs.saveAndFlush(job);
    }

    private KnowledgeDocumentResponse response(
            KnowledgeDocumentEntity document
    ) {
        UUID currentVersionId = document.getCurrentVersionId();

        if (currentVersionId == null) {
            return KnowledgeDocumentResponse.from(
                    document,
                    null,
                    null
            );
        }

        KnowledgeDocumentVersionEntity version = versions
                .findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                        currentVersionId,
                        document.getId(),
                        document.getKnowledgeBaseId(),
                        document.getOrganizationId()
                )
                .orElse(null);

        KnowledgeIngestionStatus status = version == null
                ? null
                : jobs.findByDocumentVersionIdAndOrganizationId(
                                version.getId(),
                                document.getOrganizationId()
                        )
                        .map(KnowledgeIngestionJobEntity::getStatus)
                        .orElse(null);

        return KnowledgeDocumentResponse.from(
                document,
                version,
                status
        );
    }

    private KnowledgeBaseAccessLevel authorize(
            UUID kbId,
            SafeAiUserPrincipal user,
            KnowledgeBaseAccessLevel required
    ) {
        Objects.requireNonNull(kbId, "kbId не должен быть null");
        Objects.requireNonNull(user, "user не должен быть null");
        Objects.requireNonNull(required, "required не должен быть null");

        KnowledgeBaseEntity knowledgeBase = bases
                .findByIdAndOrganizationId(
                        kbId,
                        user.getOrganizationId()
                )
                .orElseThrow(
                        () -> new ResourceNotFoundException(
                                "База знаний не найдена."
                        )
                );

        if (isAdmin(user)) {
            return KnowledgeBaseAccessLevel.OWNER;
        }

        if (!knowledgeBase.isEnabled()) {
            throw new ResourceNotFoundException(
                    "База знаний не найдена."
            );
        }

        KnowledgeBaseAccessLevel actual = memberships
                .findByKnowledgeBaseIdAndOrganizationIdAndUserId(
                        kbId,
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

        if (actual == null || rank(actual) < rank(required)) {
            throw new ForbiddenOperationException(
                    "Недостаточно прав для операции с документом."
            );
        }

        return actual;
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
                .anyMatch(
                        authority -> "ROLE_ADMIN".equals(
                                authority.getAuthority()
                        )
                );
    }

    private KnowledgeDocumentEntity requireDocument(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID organizationId
    ) {
        return documents
                .findByIdAndKnowledgeBaseIdAndOrganizationId(
                        documentId,
                        knowledgeBaseId,
                        organizationId
                )
                .orElseThrow(
                        () -> new ResourceNotFoundException(
                                "Документ не найден."
                        )
                );
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new BadRequestException(
                    "page не должен быть отрицательным"
            );
        }

        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException(
                    "size должен быть в диапазоне 1.."
                            + MAX_PAGE_SIZE
            );
        }
    }

    private void registerRollbackCleanup(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteStoredObjectQuietly(storageKey);
            throw new IllegalStateException(
                    "Загрузка документа должна выполняться внутри транзакции"
            );
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            deleteStoredObjectQuietly(storageKey);
                        }
                    }
                }
        );
    }

    private void deleteStoredObjectQuietly(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (IOException exception) {
            log.error(
                    "Не удалось удалить объект после rollback: storageKey={}",
                    storageKey,
                    exception
            );
        }
    }

    private static void closeQuietly(StoredObject object) {
        try {
            InputStream stream = object.resource().getInputStream();
            stream.close();
        } catch (IOException ignored) {
            // Основное исключение важнее ошибки закрытия потока.
        }
    }

    public record Download(
            StoredObject object,
            String filename,
            String mediaType
    ) {
    }
}
