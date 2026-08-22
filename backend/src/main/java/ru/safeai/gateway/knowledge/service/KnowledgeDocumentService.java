package ru.safeai.gateway.knowledge.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.service.BestEffortStandaloneAuditService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentPageResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentVersionPageResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentVersionResponse;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeDocumentRequest;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentVersionEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentQueryRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentVersionRepository;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import ru.safeai.gateway.knowledge.storage.StoredObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeDocumentService {

    private static final int MAX_PAGE_SIZE = 100;

    private final KnowledgeAccessService accessService;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentVersionRepository versions;
    private final KnowledgeDocumentQueryRepository queryRepository;
    private final KnowledgeDocumentWriteService writeService;
    private final ObjectStorage storage;
    private final KnowledgeDocumentFileValidator fileValidator;
    private final BestEffortStandaloneAuditService audit;

    public KnowledgeDocumentService(
            KnowledgeAccessService accessService,
            KnowledgeDocumentRepository documents,
            KnowledgeDocumentVersionRepository versions,
            KnowledgeDocumentQueryRepository queryRepository,
            KnowledgeDocumentWriteService writeService,
            ObjectStorage storage,
            KnowledgeDocumentFileValidator fileValidator,
            BestEffortStandaloneAuditService audit
    ) {
        this.accessService = accessService;
        this.documents = documents;
        this.versions = versions;
        this.queryRepository = queryRepository;
        this.writeService = writeService;
        this.storage = storage;
        this.fileValidator = fileValidator;
        this.audit = audit;
    }

    public KnowledgeDocumentPageResponse list(
            UUID knowledgeBaseId,
            SafeAiUserPrincipal user,
            int page,
            int size
    ) {
        KnowledgeAccessService.Access access =
                accessService.requireAccess(
                        knowledgeBaseId,
                        user,
                        KnowledgeBaseAccessLevel.VIEWER
                );

        validatePage(
                page,
                size
        );

        /*
         * VIEWER видит только enabled content.
         * EDITOR/OWNER и tenant ADMIN видят disabled документы для
         * content-administration/recovery.
         */
        boolean includeDisabled =
                access.administrator()
                        || access.atLeast(
                        KnowledgeBaseAccessLevel.EDITOR
                );

        return queryRepository.list(
                user.getOrganizationId(),
                knowledgeBaseId,
                page,
                size,
                includeDisabled
        );
    }

    public KnowledgeDocumentVersionPageResponse listVersions(
            UUID knowledgeBaseId,
            UUID documentId,
            SafeAiUserPrincipal user,
            int page,
            int size
    ) {
        accessService.requireAccess(
                knowledgeBaseId,
                user,
                KnowledgeBaseAccessLevel.EDITOR
        );

        validatePage(
                page,
                size
        );

        requireDocument(
                knowledgeBaseId,
                documentId,
                user.getOrganizationId()
        );

        return KnowledgeDocumentVersionPageResponse.from(
                versions
                        .findAllByDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                                documentId,
                                knowledgeBaseId,
                                user.getOrganizationId(),
                                PageRequest.of(
                                        page,
                                        size,
                                        Sort.by(
                                                Sort.Order.desc(
                                                        "versionNumber"
                                                ),
                                                Sort.Order.desc(
                                                        "id"
                                                )
                                        )
                                )
                        )
                        .map(
                                KnowledgeDocumentVersionResponse::from
                        )
        );
    }


    public KnowledgeDocumentResponse update(
            UUID knowledgeBaseId,
            UUID documentId,
            UpdateKnowledgeDocumentRequest request,
            SafeAiUserPrincipal user
    ) {
        return writeService.updateDocument(
                knowledgeBaseId,
                documentId,
                request,
                user
        );
    }

    public KnowledgeDocumentResponse uploadNew(
            UUID knowledgeBaseId,
            String requestedName,
            MultipartFile file,
            SafeAiUserPrincipal user
    ) {
        /*
         * Быстрая initial auth до file I/O. После storage.put writeService
         * ОБЯЗАТЕЛЬНО повторяет authorization уже внутри финальной DB TX.
         */
        accessService.requireAccess(
                knowledgeBaseId,
                user,
                KnowledgeBaseAccessLevel.EDITOR
        );

        KnowledgeDocumentFileValidator.ValidatedUpload upload =
                fileValidator.validate(
                        file
                );

        String name =
                KnowledgeDocumentNameNormalizer.normalize(
                        requestedName == null
                                || requestedName.isBlank()
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

        UUID documentId =
                UUID.randomUUID();
        UUID versionId =
                UUID.randomUUID();

        String storageKey =
                storageKey(
                        user.getOrganizationId(),
                        knowledgeBaseId,
                        documentId,
                        versionId
                );

        putObject(
                storageKey,
                upload
        );

        try {
            return writeService.createInitialVersion(
                    knowledgeBaseId,
                    documentId,
                    versionId,
                    name,
                    upload,
                    storageKey,
                    user
            );
        } catch (RuntimeException exception) {
            deleteObjectQuietly(
                    storageKey,
                    exception
            );
            throw exception;
        }
    }

    public KnowledgeDocumentResponse uploadVersion(
            UUID knowledgeBaseId,
            UUID documentId,
            MultipartFile file,
            SafeAiUserPrincipal user
    ) {
        accessService.requireAccess(
                knowledgeBaseId,
                user,
                KnowledgeBaseAccessLevel.EDITOR
        );

        requireDocument(
                knowledgeBaseId,
                documentId,
                user.getOrganizationId()
        );

        KnowledgeDocumentFileValidator.ValidatedUpload upload =
                fileValidator.validate(
                        file
                );

        UUID versionId =
                UUID.randomUUID();

        String storageKey =
                storageKey(
                        user.getOrganizationId(),
                        knowledgeBaseId,
                        documentId,
                        versionId
                );

        putObject(
                storageKey,
                upload
        );

        try {
            return writeService.addVersion(
                    knowledgeBaseId,
                    documentId,
                    versionId,
                    upload,
                    storageKey,
                    user
            );
        } catch (RuntimeException exception) {
            deleteObjectQuietly(
                    storageKey,
                    exception
            );
            throw exception;
        }
    }

    public Download download(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId,
            SafeAiUserPrincipal user
    ) {
        KnowledgeBaseAccessLevel required =
                versionId == null
                        ? KnowledgeBaseAccessLevel.VIEWER
                        : KnowledgeBaseAccessLevel.EDITOR;

        KnowledgeAccessService.Access access =
                accessService.requireAccess(
                        knowledgeBaseId,
                        user,
                        required
                );

        KnowledgeDocumentEntity document =
                requireDocument(
                        knowledgeBaseId,
                        documentId,
                        user.getOrganizationId()
                );

        if (!document.isEnabled()
                && !access.administrator()
                && !access.atLeast(
                KnowledgeBaseAccessLevel.EDITOR
        )) {
            throw documentNotFound();
        }

        UUID selectedVersionId =
                versionId == null
                        ? document.getCurrentVersionId()
                        : versionId;

        if (selectedVersionId == null) {
            throw versionNotFound();
        }

        KnowledgeDocumentVersionEntity version =
                versions
                        .findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                                selectedVersionId,
                                documentId,
                                knowledgeBaseId,
                                user.getOrganizationId()
                        )
                        .orElseThrow(
                                this::versionNotFound
                        );

        final StoredObject object;

        try {
            object = storage.get(
                    version.getStorageKey()
            );
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

        audit.tryRecord(
                AuditActor.fromPrincipal(user),
                user.getOrganizationId(),
                AuditEventType.KNOWLEDGE_DOCUMENT_DOWNLOADED,
                Map.of(
                        "knowledgeBaseId",
                        knowledgeBaseId.toString(),
                        "documentId",
                        documentId.toString(),
                        "documentVersionId",
                        version.getId().toString(),
                        "historicalVersion",
                        versionId != null
                )
        );

        return new Download(
                object,
                version.getOriginalFilename(),
                version.getMediaType()
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
                        this::documentNotFound
                );
    }

    private void putObject(
            String storageKey,
            KnowledgeDocumentFileValidator.ValidatedUpload upload
    ) {
        byte[] bytes =
                upload.bytes();

        try {
            storage.put(
                    storageKey,
                    new ByteArrayInputStream(
                            bytes
                    )
            );
        } catch (IOException exception) {
            throw new BadRequestException(
                    "Не удалось сохранить файл в объектном хранилище.",
                    exception
            );
        }
    }

    private void deleteObjectQuietly(
            String storageKey,
            RuntimeException original
    ) {
        try {
            storage.delete(
                    storageKey
            );
        } catch (IOException cleanupFailure) {
            original.addSuppressed(
                    cleanupFailure
            );
        }
    }

    private static String storageKey(
            UUID organizationId,
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId
    ) {
        return organizationId
                + "/"
                + knowledgeBaseId
                + "/"
                + documentId
                + "/"
                + versionId;
    }

    private static void validatePage(
            int page,
            int size
    ) {
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

    private ConflictException duplicateDocumentName() {
        return new ConflictException(
                "Документ с таким названием уже существует."
        );
    }

    private ResourceNotFoundException documentNotFound() {
        return new ResourceNotFoundException(
                "Документ не найден."
        );
    }

    private ResourceNotFoundException versionNotFound() {
        return new ResourceNotFoundException(
                "Версия документа не найдена."
        );
    }

    public record Download(
            StoredObject object,
            String filename,
            String mediaType
    ) {
    }
}
