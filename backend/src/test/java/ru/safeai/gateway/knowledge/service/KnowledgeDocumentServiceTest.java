package ru.safeai.gateway.knowledge.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentServiceTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID KB_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final UUID DOCUMENT_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
            );

    @Mock
    private KnowledgeBaseRepository bases;

    @Mock
    private KnowledgeBaseMembershipRepository memberships;

    @Mock
    private KnowledgeDocumentRepository documents;

    @Mock
    private KnowledgeDocumentVersionRepository versions;

    @Mock
    private KnowledgeIngestionJobRepository jobs;

    @Mock
    private ObjectStorage storage;

    @Mock
    private KnowledgeDocumentFileValidator fileValidator;

    @Mock
    private AuditEventService audit;

    private KnowledgeDocumentService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeDocumentService(
                bases,
                memberships,
                documents,
                versions,
                jobs,
                storage,
                fileValidator,
                audit
        );
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager
                .isSynchronizationActive()) {
            TransactionSynchronizationManager
                    .clearSynchronization();
        }
    }

    @Test
    void uploadNew_persistsMetadataObjectHashAndPendingJob()
            throws IOException {
        beginSynchronization();

        byte[] bytes =
                "SafeAI document"
                        .getBytes(StandardCharsets.UTF_8);

        MockMultipartFile multipart =
                new MockMultipartFile(
                        "file",
                        "runbook.txt",
                        "text/plain",
                        bytes
                );

        stubBaseForAdmin();

        when(fileValidator.validate(multipart))
                .thenReturn(
                        new KnowledgeDocumentFileValidator.ValidatedUpload(
                                bytes,
                                "runbook.txt",
                                "text/plain",
                                "abc123"
                        )
                );

        when(
                documents
                        .existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(
                                KB_ID,
                                ORGANIZATION_ID,
                                "Production Runbook"
                        )
        ).thenReturn(false);

        stubDocumentPersistence();

        when(
                documents.currentVersionNumber(
                        DOCUMENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(0);

        when(
                versions.saveAndFlush(
                        any(KnowledgeDocumentVersionEntity.class)
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        when(
                jobs.saveAndFlush(
                        any(KnowledgeIngestionJobEntity.class)
                )
        ).thenAnswer(
                invocation -> {
                    KnowledgeIngestionJobEntity job =
                            invocation.getArgument(0);

                    job.setStatus(
                            KnowledgeIngestionStatus.PENDING
                    );

                    return job;
                }
        );

        var response =
                service.uploadNew(
                        KB_ID,
                        "  Production   Runbook  ",
                        multipart,
                        admin()
                );

        assertThat(response.id())
                .isEqualTo(DOCUMENT_ID);

        assertThat(response.name())
                .isEqualTo("Production Runbook");

        assertThat(response.versionNumber())
                .isEqualTo(1);

        assertThat(response.originalFilename())
                .isEqualTo("runbook.txt");

        assertThat(response.mediaType())
                .isEqualTo("text/plain");

        assertThat(response.sizeBytes())
                .isEqualTo(bytes.length);

        assertThat(response.status())
                .isEqualTo(
                        KnowledgeIngestionStatus.PENDING
                );

        ArgumentCaptor<String> storageKey =
                ArgumentCaptor.forClass(
                        String.class
                );

        verify(storage).put(
                storageKey.capture(),
                any()
        );

        assertThat(storageKey.getValue())
                .startsWith(
                        ORGANIZATION_ID
                                + "/"
                                + KB_ID
                                + "/"
                                + DOCUMENT_ID
                                + "/"
                );

        ArgumentCaptor<KnowledgeDocumentVersionEntity>
                versionCaptor =
                ArgumentCaptor.forClass(
                        KnowledgeDocumentVersionEntity.class
                );

        verify(versions)
                .saveAndFlush(
                        versionCaptor.capture()
                );

        KnowledgeDocumentVersionEntity version =
                versionCaptor.getValue();

        assertThat(version.getOrganizationId())
                .isEqualTo(ORGANIZATION_ID);

        assertThat(version.getKnowledgeBaseId())
                .isEqualTo(KB_ID);

        assertThat(version.getDocumentId())
                .isEqualTo(DOCUMENT_ID);

        assertThat(version.getVersionNumber())
                .isEqualTo(1);

        assertThat(version.getSha256())
                .isEqualTo("abc123");

        assertThat(version.getStorageKey())
                .isEqualTo(storageKey.getValue());

        verify(audit).record(
                any(SafeAiUserPrincipal.class),
                eq(ORGANIZATION_ID),
                eq(
                        AuditEventType
                                .KNOWLEDGE_DOCUMENT_CREATED
                ),
                anyMap()
        );

        completeSynchronization(
                TransactionSynchronization.STATUS_COMMITTED
        );

        verify(
                storage,
                never()
        ).delete(anyString());
    }

    @Test
    void uploadNew_duplicateNameDoesNotTouchStorage() {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "runbook.txt",
                        "text/plain",
                        "a".getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        stubBaseForAdmin();

        when(fileValidator.validate(file))
                .thenReturn(
                        new KnowledgeDocumentFileValidator.ValidatedUpload(
                                new byte[]{1},
                                "runbook.txt",
                                "text/plain",
                                "hash"
                        )
                );

        when(
                documents
                        .existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(
                                KB_ID,
                                ORGANIZATION_ID,
                                "runbook.txt"
                        )
        ).thenReturn(true);

        assertThatThrownBy(
                () -> service.uploadNew(
                        KB_ID,
                        null,
                        file,
                        admin()
                )
        ).isInstanceOf(
                ConflictException.class
        );

        verifyNoInteractions(storage);

        verify(
                documents,
                never()
        ).saveAndFlush(any());
    }

    @Test
    void uploadNew_storageFailureStopsBeforeVersionAndJobCreation()
            throws IOException {
        beginSynchronization();

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "runbook.txt",
                        "text/plain",
                        "a".getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        stubBaseForAdmin();

        when(fileValidator.validate(file))
                .thenReturn(
                        new KnowledgeDocumentFileValidator.ValidatedUpload(
                                new byte[]{1},
                                "runbook.txt",
                                "text/plain",
                                "hash"
                        )
                );

        when(
                documents
                        .existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(
                                KB_ID,
                                ORGANIZATION_ID,
                                "runbook.txt"
                        )
        ).thenReturn(false);

        stubDocumentPersistence();

        doThrow(
                new IOException("storage down")
        )
                .when(storage)
                .put(
                        anyString(),
                        any()
                );

        assertThatThrownBy(
                () -> service.uploadNew(
                        KB_ID,
                        null,
                        file,
                        admin()
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(
                        "объектном хранилище"
                );

        verifyNoInteractions(versions);
        verifyNoInteractions(jobs);
    }

    @Test
    void uploadNew_registeredObjectIsDeletedOnTransactionRollback()
            throws IOException {
        beginSynchronization();

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "runbook.txt",
                        "text/plain",
                        "a".getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        stubSuccessfulNewUpload(file);

        service.uploadNew(
                KB_ID,
                null,
                file,
                admin()
        );

        ArgumentCaptor<String> key =
                ArgumentCaptor.forClass(
                        String.class
                );

        verify(storage).put(
                key.capture(),
                any()
        );

        completeSynchronization(
                TransactionSynchronization.STATUS_ROLLED_BACK
        );

        verify(storage).delete(
                key.getValue()
        );
    }

    @Test
    void uploadVersion_usesLockedDocumentAndNextVersionNumber() {
        beginSynchronization();

        KnowledgeDocumentEntity document =
                existingDocument();

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "runbook-v4.txt",
                        "text/plain",
                        "v4".getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        stubBaseForAdmin();

        when(fileValidator.validate(file))
                .thenReturn(
                        new KnowledgeDocumentFileValidator.ValidatedUpload(
                                "v4".getBytes(
                                        StandardCharsets.UTF_8
                                ),
                                "runbook-v4.txt",
                                "text/plain",
                                "hash-v4"
                        )
                );

        when(
                documents.findForUpdate(
                        DOCUMENT_ID,
                        KB_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                Optional.of(document)
        );

        when(
                documents.currentVersionNumber(
                        DOCUMENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(3);

        when(
                versions.saveAndFlush(any())
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        when(
                jobs.saveAndFlush(any())
        ).thenAnswer(
                invocation -> {
                    KnowledgeIngestionJobEntity job =
                            invocation.getArgument(0);

                    job.setStatus(
                            KnowledgeIngestionStatus.PENDING
                    );

                    return job;
                }
        );

        when(
                documents.saveAndFlush(any())
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        var response =
                service.uploadVersion(
                        KB_ID,
                        DOCUMENT_ID,
                        file,
                        admin()
                );

        assertThat(response.versionNumber())
                .isEqualTo(4);

        verify(documents)
                .findForUpdate(
                        DOCUMENT_ID,
                        KB_ID,
                        ORGANIZATION_ID
                );

        completeSynchronization(
                TransactionSynchronization.STATUS_COMMITTED
        );
    }

    @Test
    void upload_isForbiddenForImplicitOrganizationViewer() {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "a.txt",
                        "text/plain",
                        new byte[]{1}
                );

        when(
                bases.findByIdAndOrganizationId(
                        KB_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                Optional.of(
                        enabledBase(
                                KnowledgeBaseVisibility.ORGANIZATION
                        )
                )
        );

        when(
                memberships
                        .findByKnowledgeBaseIdAndOrganizationIdAndUserId(
                                KB_ID,
                                ORGANIZATION_ID,
                                USER_ID
                        )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(
                () -> service.uploadNew(
                        KB_ID,
                        null,
                        file,
                        user()
                )
        )
                .isInstanceOf(
                        ForbiddenOperationException.class
                )
                .hasMessageContaining(
                        "Недостаточно прав"
                );

        verifyNoInteractions(fileValidator);
    }

    @Test
    void list_implicitViewerSeesOnlyEnabledDocuments() {
        when(
                bases.findByIdAndOrganizationId(
                        KB_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                Optional.of(
                        enabledBase(
                                KnowledgeBaseVisibility.ORGANIZATION
                        )
                )
        );

        when(
                memberships
                        .findByKnowledgeBaseIdAndOrganizationIdAndUserId(
                                KB_ID,
                                ORGANIZATION_ID,
                                USER_ID
                        )
        ).thenReturn(
                Optional.empty()
        );

        when(
                documents
                        .findAllByKnowledgeBaseIdAndOrganizationIdAndEnabledTrue(
                                eq(KB_ID),
                                eq(ORGANIZATION_ID),
                                any()
                        )
        ).thenReturn(
                new PageImpl<>(
                        List.of()
                )
        );

        service.list(
                KB_ID,
                user(),
                0,
                50
        );

        verify(documents)
                .findAllByKnowledgeBaseIdAndOrganizationIdAndEnabledTrue(
                        eq(KB_ID),
                        eq(ORGANIZATION_ID),
                        any()
                );

        verify(
                documents,
                never()
        ).findAllByKnowledgeBaseIdAndOrganizationId(
                any(),
                any(),
                any()
        );
    }

    @Test
    void list_ownerSeesDisabledAndEnabledDocuments() {
        KnowledgeBaseMembershipEntity membership =
                new KnowledgeBaseMembershipEntity();

        membership.setAccessLevel(
                KnowledgeBaseAccessLevel.OWNER
        );

        when(
                bases.findByIdAndOrganizationId(
                        KB_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                Optional.of(
                        enabledBase(
                                KnowledgeBaseVisibility.MEMBERS
                        )
                )
        );

        when(
                memberships
                        .findByKnowledgeBaseIdAndOrganizationIdAndUserId(
                                KB_ID,
                                ORGANIZATION_ID,
                                USER_ID
                        )
        ).thenReturn(
                Optional.of(membership)
        );

        when(
                documents
                        .findAllByKnowledgeBaseIdAndOrganizationId(
                                eq(KB_ID),
                                eq(ORGANIZATION_ID),
                                any()
                        )
        ).thenReturn(
                new PageImpl<>(
                        List.of()
                )
        );

        service.list(
                KB_ID,
                user(),
                0,
                50
        );

        verify(documents)
                .findAllByKnowledgeBaseIdAndOrganizationId(
                        eq(KB_ID),
                        eq(ORGANIZATION_ID),
                        any()
                );
    }

    @Test
    void list_rejectsInvalidPageSize() {
        stubBaseForAdmin();

        assertThatThrownBy(
                () -> service.list(
                        KB_ID,
                        admin(),
                        0,
                        101
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1..100");
    }

    @Test
    void download_missingStorageObjectReturnsNotFound()
            throws IOException {
        KnowledgeDocumentEntity document =
                existingDocument();

        UUID versionId =
                UUID.randomUUID();

        document.setCurrentVersionId(
                versionId
        );

        KnowledgeDocumentVersionEntity version =
                version(
                        versionId,
                        "key/missing"
                );

        stubDownloadAuthorization(
                document,
                version
        );

        when(
                storage.get("key/missing")
        ).thenThrow(
                new NoSuchFileException(
                        "key/missing"
                )
        );

        assertThatThrownBy(
                () -> service.download(
                        KB_ID,
                        DOCUMENT_ID,
                        null,
                        admin()
                )
        )
                .isInstanceOf(
                        ResourceNotFoundException.class
                )
                .hasMessageContaining(
                        "отсутствует"
                );
    }

    @Test
    void download_storageFailureIsNotMasqueradedAs404()
            throws IOException {
        KnowledgeDocumentEntity document =
                existingDocument();

        UUID versionId =
                UUID.randomUUID();

        document.setCurrentVersionId(
                versionId
        );

        KnowledgeDocumentVersionEntity version =
                version(
                        versionId,
                        "key/object"
                );

        stubDownloadAuthorization(
                document,
                version
        );

        when(
                storage.get("key/object")
        ).thenThrow(
                new IOException(
                        "S3 timeout"
                )
        );

        assertThatThrownBy(
                () -> service.download(
                        KB_ID,
                        DOCUMENT_ID,
                        null,
                        admin()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "временно недоступно"
                );
    }

    @Test
    void download_currentVersionReturnsStoredObjectAndAudits()
            throws IOException {
        KnowledgeDocumentEntity document =
                existingDocument();

        UUID versionId =
                UUID.randomUUID();

        document.setCurrentVersionId(
                versionId
        );

        KnowledgeDocumentVersionEntity version =
                version(
                        versionId,
                        "key/object"
                );

        stubDownloadAuthorization(
                document,
                version
        );

        byte[] bytes =
                "download".getBytes(
                        StandardCharsets.UTF_8
                );

        StoredObject stored =
                new StoredObject(
                        new ByteArrayResource(
                                bytes
                        ),
                        bytes.length
                );

        when(
                storage.get("key/object")
        ).thenReturn(stored);

        var result =
                service.download(
                        KB_ID,
                        DOCUMENT_ID,
                        null,
                        admin()
                );

        assertThat(result.object())
                .isSameAs(stored);

        assertThat(result.filename())
                .isEqualTo("runbook.txt");

        assertThat(result.mediaType())
                .isEqualTo("text/plain");

        verify(audit).record(
                any(SafeAiUserPrincipal.class),
                eq(ORGANIZATION_ID),
                eq(
                        AuditEventType
                                .KNOWLEDGE_DOCUMENT_DOWNLOADED
                ),
                anyMap()
        );
    }

    private void stubSuccessfulNewUpload(
            MockMultipartFile file
    ) throws IOException {
        stubBaseForAdmin();

        when(fileValidator.validate(file))
                .thenReturn(
                        new KnowledgeDocumentFileValidator.ValidatedUpload(
                                file.getBytes(),
                                "runbook.txt",
                                "text/plain",
                                "hash"
                        )
                );

        when(
                documents
                        .existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(
                                KB_ID,
                                ORGANIZATION_ID,
                                "runbook.txt"
                        )
        ).thenReturn(false);

        stubDocumentPersistence();

        when(
                documents.currentVersionNumber(
                        DOCUMENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(0);

        when(
                versions.saveAndFlush(any())
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        when(
                jobs.saveAndFlush(any())
        ).thenAnswer(
                invocation -> {
                    KnowledgeIngestionJobEntity job =
                            invocation.getArgument(0);

                    job.setStatus(
                            KnowledgeIngestionStatus.PENDING
                    );

                    return job;
                }
        );
    }

    private void stubDocumentPersistence() {
        when(
                documents.saveAndFlush(
                        any(KnowledgeDocumentEntity.class)
                )
        ).thenAnswer(
                invocation -> {
                    KnowledgeDocumentEntity document =
                            invocation.getArgument(0);

                    if (document.getId() == null) {
                        document.setId(
                                DOCUMENT_ID
                        );
                    }

                    return document;
                }
        );
    }

    private void stubBaseForAdmin() {
        when(
                bases.findByIdAndOrganizationId(
                        KB_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                Optional.of(
                        enabledBase(
                                KnowledgeBaseVisibility.ORGANIZATION
                        )
                )
        );
    }

    private void stubDownloadAuthorization(
            KnowledgeDocumentEntity document,
            KnowledgeDocumentVersionEntity version
    ) {
        stubBaseForAdmin();

        when(
                documents
                        .findByIdAndKnowledgeBaseIdAndOrganizationId(
                                DOCUMENT_ID,
                                KB_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(document)
        );

        when(
                versions
                        .findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                                version.getId(),
                                DOCUMENT_ID,
                                KB_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(version)
        );
    }

    private KnowledgeBaseEntity enabledBase(
            KnowledgeBaseVisibility visibility
    ) {
        KnowledgeBaseEntity base =
                new KnowledgeBaseEntity();

        base.setId(KB_ID);
        base.setOrganizationId(
                ORGANIZATION_ID
        );
        base.setVisibility(visibility);
        base.setEnabled(true);
        base.setCreatedByUserId(USER_ID);
        base.setName("KB");

        return base;
    }

    private KnowledgeDocumentEntity existingDocument() {
        KnowledgeDocumentEntity document =
                new KnowledgeDocumentEntity();

        document.setId(DOCUMENT_ID);
        document.setOrganizationId(
                ORGANIZATION_ID
        );
        document.setKnowledgeBaseId(KB_ID);
        document.setName("Runbook");
        document.setEnabled(true);
        document.setCreatedByUserId(USER_ID);

        return document;
    }

    private KnowledgeDocumentVersionEntity version(
            UUID versionId,
            String storageKey
    ) {
        KnowledgeDocumentVersionEntity version =
                new KnowledgeDocumentVersionEntity();

        version.setId(versionId);
        version.setOrganizationId(
                ORGANIZATION_ID
        );
        version.setKnowledgeBaseId(KB_ID);
        version.setDocumentId(DOCUMENT_ID);
        version.setVersionNumber(1);
        version.setOriginalFilename(
                "runbook.txt"
        );
        version.setMediaType("text/plain");
        version.setSizeBytes(8);
        version.setSha256(
                "a".repeat(64)
        );
        version.setStorageKey(storageKey);
        version.setCreatedByUserId(USER_ID);

        return version;
    }

    private SafeAiUserPrincipal admin() {
        return principal("ROLE_ADMIN");
    }

    private SafeAiUserPrincipal user() {
        return principal("ROLE_USER");
    }

    private SafeAiUserPrincipal principal(
            String role
    ) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                0L,
                0L,
                List.of(
                        new SimpleGrantedAuthority(
                                role
                        )
                )
        );
    }

    private static void beginSynchronization() {
        TransactionSynchronizationManager
                .initSynchronization();
    }

    private static void completeSynchronization(
            int status
    ) {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager
                        .getSynchronizations();

        TransactionSynchronizationManager
                .clearSynchronization();

        synchronizations.forEach(
                synchronization ->
                        synchronization.afterCompletion(
                                status
                        )
        );
    }
}