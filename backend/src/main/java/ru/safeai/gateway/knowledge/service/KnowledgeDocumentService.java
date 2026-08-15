package ru.safeai.gateway.knowledge.service;

import lombok.RequiredArgsConstructor;
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
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageProperties;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import ru.safeai.gateway.knowledge.storage.StoredObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    private final KnowledgeBaseRepository bases;
    private final KnowledgeBaseMembershipRepository memberships;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentVersionRepository versions;
    private final KnowledgeIngestionJobRepository jobs;
    private final ObjectStorage storage;
    private final KnowledgeStorageProperties properties;
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

        PageRequest request = PageRequest.of(
                page,
                Math.min(size, 100),
                Sort.by(
                        Sort.Direction.DESC,
                        "updatedAt"
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

        List<KnowledgeDocumentResponse> content =
                result.getContent()
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

        String name = normalizeName(
                requestedName == null
                        || requestedName.isBlank()
                        ? file.getOriginalFilename()
                        : requestedName
        );

        if (
                documents.existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(
                        kbId,
                        user.getOrganizationId(),
                        name
                )
        ) {
            throw new ConflictException(
                    "Документ с таким названием уже существует."
            );
        }

        KnowledgeDocumentEntity document =
                new KnowledgeDocumentEntity();

        document.setOrganizationId(
                user.getOrganizationId()
        );
        document.setKnowledgeBaseId(kbId);
        document.setName(name);
        document.setEnabled(true);
        document.setCreatedByUserId(
                user.getId()
        );

        documents.saveAndFlush(document);

        KnowledgeDocumentResponse result =
                storeVersion(
                        document,
                        file,
                        user
                );

        audit.record(
                user,
                user.getOrganizationId(),
                AuditEventType.KNOWLEDGE_DOCUMENT_CREATED,
                Map.of(
                        "knowledgeBaseId",
                        kbId.toString(),
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

        KnowledgeDocumentEntity document =
                requireDocument(
                        kbId,
                        documentId,
                        user.getOrganizationId()
                );

        return storeVersion(
                document,
                file,
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

        KnowledgeDocumentEntity document =
                requireDocument(
                        kbId,
                        documentId,
                        user.getOrganizationId()
                );

        if (
                !document.isEnabled()
                        && !isAdmin(user)
        ) {
            throw new ResourceNotFoundException(
                    "Документ не найден."
            );
        }

        UUID selectedVersionId =
                versionId == null
                        ? document.getCurrentVersionId()
                        : versionId;

        KnowledgeDocumentVersionEntity version =
                versions
                        .findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                                selectedVersionId,
                                documentId,
                                kbId,
                                user.getOrganizationId()
                        )
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "Версия документа не найдена."
                                        )
                        );

        try {
            Download result = new Download(
                    storage.get(
                            version.getStorageKey()
                    ),
                    version.getOriginalFilename(),
                    version.getMediaType()
            );

            audit.record(
                    user,
                    user.getOrganizationId(),
                    AuditEventType.KNOWLEDGE_DOCUMENT_DOWNLOADED,
                    Map.of(
                            "knowledgeBaseId",
                            kbId.toString(),
                            "documentId",
                            documentId.toString(),
                            "documentVersionId",
                            version.getId().toString()
                    )
            );

            return result;
        } catch (IOException exception) {
            throw new ResourceNotFoundException(
                    "Файл документа недоступен."
            );
        }
    }

    private KnowledgeDocumentResponse storeVersion(
            KnowledgeDocumentEntity document,
            MultipartFile file,
            SafeAiUserPrincipal user
    ) {
        byte[] bytes = validate(file);

        int versionNumber =
                documents.currentVersionNumber(
                        document.getId(),
                        user.getOrganizationId()
                ) + 1;

        UUID versionId = UUID.randomUUID();

        String storageKey =
                user.getOrganizationId()
                        + "/"
                        + document.getKnowledgeBaseId()
                        + "/"
                        + document.getId()
                        + "/"
                        + versionId;

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
                    safeFilename(
                            file.getOriginalFilename()
                    )
            );
            version.setMediaType(
                    detectType(bytes)
            );
            version.setSizeBytes(
                    bytes.length
            );
            version.setSha256(
                    sha256(bytes)
            );
            version.setStorageKey(
                    storageKey
            );
            version.setCreatedByUserId(
                    user.getId()
            );

            versions.saveAndFlush(version);

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

            audit.record(
                    user,
                    user.getOrganizationId(),
                    AuditEventType.KNOWLEDGE_DOCUMENT_VERSION_UPLOADED,
                    Map.of(
                            "documentId",
                            document.getId().toString(),
                            "documentVersionId",
                            versionId.toString(),
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
            deleteStoredObjectQuietly(
                    storageKey
            );

            throw exception;
        }
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

        return jobs.saveAndFlush(job);
    }

    private void deleteStoredObjectQuietly(
            String storageKey
    ) {
        try {
            storage.delete(
                    storageKey
            );
        } catch (IOException ignored) {
            // Best-effort compensation.
            // Исходное исключение важнее ошибки cleanup.
        }
    }

    private KnowledgeDocumentResponse response(
            KnowledgeDocumentEntity document
    ) {
        KnowledgeDocumentVersionEntity version =
                document.getCurrentVersionId() == null
                        ? null
                        : versions
                                .findById(
                                        document.getCurrentVersionId()
                                )
                                .orElse(null);

        KnowledgeIngestionStatus status =
                version == null
                        ? null
                        : jobs
                                .findByDocumentVersionIdAndOrganizationId(
                                        version.getId(),
                                        document.getOrganizationId()
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

    private KnowledgeBaseAccessLevel authorize(
            UUID kbId,
            SafeAiUserPrincipal user,
            KnowledgeBaseAccessLevel required
    ) {
        KnowledgeBaseEntity knowledgeBase =
                bases
                        .findByIdAndOrganizationId(
                                kbId,
                                user.getOrganizationId()
                        )
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
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

        KnowledgeBaseAccessLevel actual =
                memberships
                        .findByKnowledgeBaseIdAndOrganizationIdAndUserId(
                                kbId,
                                user.getOrganizationId(),
                                user.getId()
                        )
                        .map(
                                KnowledgeBaseMembershipEntity::getAccessLevel
                        )
                        .orElse(
                                knowledgeBase.getVisibility()
                                        == KnowledgeBaseVisibility.ORGANIZATION
                                        ? KnowledgeBaseAccessLevel.VIEWER
                                        : null
                        );

        if (
                actual == null
                        || rank(actual) < rank(required)
        ) {
            throw new ForbiddenOperationException(
                    "Недостаточно прав для операции с документом."
            );
        }

        return actual;
    }

    private static int rank(
            KnowledgeBaseAccessLevel level
    ) {
        return switch (level) {
            case VIEWER -> 1;
            case EDITOR -> 2;
            case OWNER -> 3;
        };
    }

    private static boolean isAdmin(
            SafeAiUserPrincipal user
    ) {
        return user.getAuthorities()
                .stream()
                .anyMatch(
                        authority ->
                                "ROLE_ADMIN".equals(
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
                        () ->
                                new ResourceNotFoundException(
                                        "Документ не найден."
                                )
                );
    }

    private byte[] validate(
            MultipartFile file
    ) {
        try {
            if (
                    file == null
                            || file.isEmpty()
            ) {
                throw new BadRequestException(
                        "Выберите непустой файл."
                );
            }

            if (
                    file.getSize()
                            > properties.maxUploadBytes()
            ) {
                throw new BadRequestException(
                        "Размер файла превышает 25 МБ."
                );
            }

            byte[] bytes =
                    file.getBytes();

            detectType(bytes);

            return bytes;
        } catch (IOException exception) {
            throw new BadRequestException(
                    "Не удалось прочитать файл.",
                    exception
            );
        }
    }

    static String detectType(
            byte[] bytes
    ) {
        if (
                bytes.length >= 5
                        && bytes[0] == '%'
                        && bytes[1] == 'P'
                        && bytes[2] == 'D'
                        && bytes[3] == 'F'
                        && bytes[4] == '-'
        ) {
            return "application/pdf";
        }

        if (isDocx(bytes)) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }

        String head =
                new String(
                        bytes,
                        0,
                        Math.min(
                                bytes.length,
                                512
                        ),
                        java.nio.charset.StandardCharsets.UTF_8
                )
                        .stripLeading()
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (
                head.startsWith(
                        "<!doctype html"
                )
                        || head.startsWith(
                                "<html"
                        )
        ) {
            return "text/html";
        }

        if (
                head.chars()
                        .noneMatch(
                                character ->
                                        character < 9
                                                || (
                                                character > 13
                                                        && character < 32
                                        )
                        )
        ) {
            return "text/plain";
        }

        throw new BadRequestException(
                "Поддерживаются только PDF, DOCX, TXT и HTML."
        );
    }

    private static boolean isDocx(
            byte[] bytes
    ) {
        if (
                bytes.length < 4
                        || bytes[0] != 'P'
                        || bytes[1] != 'K'
                        || bytes[2] != 3
                        || bytes[3] != 4
        ) {
            return false;
        }

        boolean contentTypesFound = false;
        boolean documentFound = false;

        try (
                ZipInputStream zip =
                        new ZipInputStream(
                                new ByteArrayInputStream(bytes)
                        )
        ) {
            ZipEntry entry;
            int entries = 0;

            while (
                    (entry = zip.getNextEntry()) != null
                            && entries++ < 10_000
            ) {
                String name =
                        entry.getName();

                contentTypesFound |=
                        "[Content_Types].xml".equals(
                                name
                        );

                documentFound |=
                        "word/document.xml".equals(
                                name
                        );

                if (
                        contentTypesFound
                                && documentFound
                ) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // Повреждённый или некорректный ZIP
            // не считается DOCX.
        }

        return false;
    }

    private static String normalizeName(
            String value
    ) {
        if (value == null) {
            throw new BadRequestException(
                    "Введите название документа."
            );
        }

        String normalized =
                value.strip();

        if (
                normalized.isEmpty()
                        || normalized.length() > 255
                        || normalized
                                .chars()
                                .anyMatch(
                                        Character::isISOControl
                                )
        ) {
            throw new BadRequestException(
                    "Некорректное название документа."
            );
        }

        return normalized;
    }

    private static String safeFilename(
            String value
    ) {
        if (
                value == null
                        || value.isBlank()
        ) {
            return "document";
        }

        String normalized =
                value.replace(
                        '\\',
                        '/'
                );

        normalized =
                normalized
                        .substring(
                                normalized.lastIndexOf('/')
                                        + 1
                        )
                        .strip();

        return normalizeName(
                normalized
        );
    }

    private static String sha256(
            byte[] bytes
    ) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            return HexFormat.of()
                    .formatHex(
                            digest.digest(bytes)
                    );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 недоступен в текущем Java runtime",
                    exception
            );
        }
    }

    public record Download(
            StoredObject object,
            String filename,
            String mediaType
    ) {
    }
}