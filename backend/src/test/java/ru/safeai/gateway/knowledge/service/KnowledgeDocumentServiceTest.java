package ru.safeai.gateway.knowledge.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.audit.service.BestEffortStandaloneAuditService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentPageResponse;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentVersionEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeIngestionJobEntity;
import ru.safeai.gateway.knowledge.exception.KnowledgeStorageUnavailableException;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.knowledge.repository.KnowledgeBaseMembershipRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeBaseRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentQueryRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentVersionRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeIngestionJobRepository;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageProperties;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import ru.safeai.gateway.knowledge.storage.StoredObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    private static final UUID KNOWLEDGE_BASE_ID =
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
    private KnowledgeDocumentQueryRepository queryRepository;

    @Mock
    private ObjectStorage storage;

    @Mock
    private KnowledgeStorageProperties properties;

    @Mock
    private AuditEventService audit;

    @Mock
    private BestEffortStandaloneAuditService downloadAudit;

    private KnowledgeDocumentService service;

    @BeforeEach
    void setUp() {
        lenient()
                .when(
                        properties.maxUploadBytes()
                )
                .thenReturn(
                        25L * 1024L * 1024L
                );

        KnowledgeDocumentFileValidator fileValidator =
                new KnowledgeDocumentFileValidator(
                        properties
                );

        KnowledgeAccessService accessService =
                new KnowledgeAccessService(
                        bases,
                        memberships
                );

        KnowledgeDocumentWriteService writeService =
                new KnowledgeDocumentWriteService(
                        accessService,
                        documents,
                        versions,
                        jobs,
                        audit
                );

        service =
                new KnowledgeDocumentService(
                        accessService,
                        documents,
                        versions,
                        queryRepository,
                        writeService,
                        storage,
                        fileValidator,
                        downloadAudit
                );
    }

    @Test
    void uploadNewPersistsDocumentVersionAndPendingJob()
            throws IOException {
        MockMultipartFile file =
                textFile(
                        "runbook.txt",
                        "SafeAI production runbook"
                );

        stubBaseForAdmin();
        stubDocumentPersistence();
        stubVersionPersistence();
        stubJobPersistence();

        when(
                documents
                        .existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(
                                KNOWLEDGE_BASE_ID,
                                ORGANIZATION_ID,
                                "Production Runbook"
                        )
        ).thenReturn(
                false
        );

        var response =
                service.uploadNew(
                        KNOWLEDGE_BASE_ID,
                        "Production Runbook",
                        file,
                        admin()
                );

        assertThat(
                response.id()
        ).isNotNull();

        assertThat(
                response.knowledgeBaseId()
        ).isEqualTo(
                KNOWLEDGE_BASE_ID
        );

        assertThat(
                response.name()
        ).isEqualTo(
                "Production Runbook"
        );

        assertThat(
                response.versionNumber()
        ).isEqualTo(
                1
        );

        assertThat(
                response.originalFilename()
        ).isEqualTo(
                "runbook.txt"
        );

        assertThat(
                response.mediaType()
        ).isEqualTo(
                "text/plain"
        );

        assertThat(
                response.status()
        ).isEqualTo(
                KnowledgeIngestionStatus.PENDING
        );

        ArgumentCaptor<String> storageKeyCaptor =
                ArgumentCaptor.forClass(
                        String.class
                );

        verify(
                storage
        ).put(
                storageKeyCaptor.capture(),
                any()
        );

        String storageKey =
                storageKeyCaptor.getValue();

        assertThat(
                storageKey
        ).startsWith(
                ORGANIZATION_ID
                        + "/"
                        + KNOWLEDGE_BASE_ID
                        + "/"
                        + response.id()
                        + "/"
        );

        ArgumentCaptor<KnowledgeDocumentVersionEntity> versionCaptor =
                ArgumentCaptor.forClass(
                        KnowledgeDocumentVersionEntity.class
                );

        verify(
                versions
        ).saveAndFlush(
                versionCaptor.capture()
        );

        KnowledgeDocumentVersionEntity version =
                versionCaptor.getValue();

        assertThat(
                version.getOrganizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                version.getKnowledgeBaseId()
        ).isEqualTo(
                KNOWLEDGE_BASE_ID
        );

        assertThat(
                version.getDocumentId()
        ).isEqualTo(
                response.id()
        );

        assertThat(
                version.getVersionNumber()
        ).isEqualTo(
                1
        );

        assertThat(
                version.getSha256()
        ).hasSize(
                64
        );

        assertThat(
                version.getStorageKey()
        ).isEqualTo(
                storageKey
        );

        assertThat(
                response.currentVersionId()
        ).isEqualTo(
                version.getId()
        );

        ArgumentCaptor<KnowledgeIngestionJobEntity> jobCaptor =
                ArgumentCaptor.forClass(
                        KnowledgeIngestionJobEntity.class
                );

        verify(
                jobs
        ).saveAndFlush(
                jobCaptor.capture()
        );

        KnowledgeIngestionJobEntity job =
                jobCaptor.getValue();

        assertThat(
                job.getOrganizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                job.getKnowledgeBaseId()
        ).isEqualTo(
                KNOWLEDGE_BASE_ID
        );

        assertThat(
                job.getDocumentId()
        ).isEqualTo(
                response.id()
        );

        assertThat(
                job.getDocumentVersionId()
        ).isEqualTo(
                version.getId()
        );

        assertThat(
                job.getStatus()
        ).isEqualTo(
                KnowledgeIngestionStatus.PENDING
        );

        verify(
                documents,
                times(2)
        ).saveAndFlush(
                any(KnowledgeDocumentEntity.class)
        );

        verify(
                audit
        ).record(
                any(SafeAiUserPrincipal.class),
                eq(
                        ORGANIZATION_ID
                ),
                eq(
                        AuditEventType.KNOWLEDGE_DOCUMENT_CREATED
                ),
                anyMap()
        );

        verify(
                audit
        ).record(
                any(SafeAiUserPrincipal.class),
                eq(
                        ORGANIZATION_ID
                ),
                eq(
                        AuditEventType.KNOWLEDGE_DOCUMENT_VERSION_UPLOADED
                ),
                anyMap()
        );
    }

    @Test
    void uploadNewRejectsDuplicateNameBeforeWritingStorage() {
        MockMultipartFile file =
                textFile(
                        "runbook.txt",
                        "SafeAI production runbook"
                );

        stubBaseForAdmin();

        when(
                documents
                        .existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(
                                KNOWLEDGE_BASE_ID,
                                ORGANIZATION_ID,
                                "runbook.txt"
                        )
        ).thenReturn(
                true
        );

        assertThatThrownBy(
                () ->
                        service.uploadNew(
                                KNOWLEDGE_BASE_ID,
                                null,
                                file,
                                admin()
                        )
        ).isInstanceOf(
                ConflictException.class
        );

        verifyNoInteractions(
                storage
        );

        verify(
                versions,
                never()
        ).saveAndFlush(
                any()
        );

        verify(
                jobs,
                never()
        ).saveAndFlush(
                any()
        );
    }

    @Test
    void uploadNewStopsWhenStorageFails()
            throws IOException {
        MockMultipartFile file =
                textFile(
                        "runbook.txt",
                        "SafeAI production runbook"
                );

        stubBaseForAdmin();

        when(
                documents
                        .existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(
                                KNOWLEDGE_BASE_ID,
                                ORGANIZATION_ID,
                                "runbook.txt"
                        )
        ).thenReturn(
                false
        );

        doThrow(
                new IOException(
                        "Storage unavailable"
                )
        )
                .when(
                        storage
                )
                .put(
                        anyString(),
                        any()
                );

        /*
         * Object-storage failure is a server-side infrastructure failure,
         * not malformed client input.
         *
         * Production contract:
         *
         * IOException from storage PUT
         * -> KnowledgeStorageUnavailableException
         * -> HTTP 503 at the API boundary.
         */
        assertThatThrownBy(
                () ->
                        service.uploadNew(
                                KNOWLEDGE_BASE_ID,
                                null,
                                file,
                                admin()
                        )
        )
                .isInstanceOf(
                        KnowledgeStorageUnavailableException.class
                )
                .hasMessageContaining(
                        "Knowledge object storage PUT failed"
                )
                .hasCauseInstanceOf(
                        IOException.class
                );

        /*
         * Storage failed before DB metadata publication, therefore no version
         * or ingestion job may be persisted.
         */
        verify(
                versions,
                never()
        ).saveAndFlush(
                any()
        );

        verify(
                jobs,
                never()
        ).saveAndFlush(
                any()
        );
    }

    @Test
    void uploadVersionLocksDocumentAndUsesNextVersionNumber()
            throws IOException {
        MockMultipartFile file =
                textFile(
                        "runbook-v2.txt",
                        "Version two"
                );

        KnowledgeDocumentEntity document =
                existingDocument();

        stubBaseForAdmin();
        stubVersionPersistence();
        stubJobPersistence();

        when(
                documents
                        .findByIdAndKnowledgeBaseIdAndOrganizationId(
                                DOCUMENT_ID,
                                KNOWLEDGE_BASE_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(
                        document
                )
        );

        when(
                documents.findForUpdate(
                        DOCUMENT_ID,
                        KNOWLEDGE_BASE_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                Optional.of(
                        document
                )
        );

        when(
                documents.currentVersionNumber(
                        DOCUMENT_ID,
                        KNOWLEDGE_BASE_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                1
        );

        var response =
                service.uploadVersion(
                        KNOWLEDGE_BASE_ID,
                        DOCUMENT_ID,
                        file,
                        admin()
                );

        assertThat(
                response.versionNumber()
        ).isEqualTo(
                2
        );

        verify(
                documents
        ).findForUpdate(
                DOCUMENT_ID,
                KNOWLEDGE_BASE_ID,
                ORGANIZATION_ID
        );

        verify(
                documents
        ).currentVersionNumber(
                DOCUMENT_ID,
                KNOWLEDGE_BASE_ID,
                ORGANIZATION_ID
        );

        verify(
                storage
        ).put(
                anyString(),
                any()
        );
    }

    @Test
    void organizationViewerCannotUploadDocument() {
        MockMultipartFile file =
                textFile(
                        "runbook.txt",
                        "SafeAI production runbook"
                );

        when(
                bases.findByIdAndOrganizationId(
                        KNOWLEDGE_BASE_ID,
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
                                KNOWLEDGE_BASE_ID,
                                ORGANIZATION_ID,
                                USER_ID
                        )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(
                () ->
                        service.uploadNew(
                                KNOWLEDGE_BASE_ID,
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

        verifyNoInteractions(
                storage
        );
    }

    @Test
    void nonMemberCannotDiscoverMembersOnlyKnowledgeBase() {
        when(
                bases.findByIdAndOrganizationId(
                        KNOWLEDGE_BASE_ID,
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
                                KNOWLEDGE_BASE_ID,
                                ORGANIZATION_ID,
                                USER_ID
                        )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(
                () ->
                        service.list(
                                KNOWLEDGE_BASE_ID,
                                user(),
                                0,
                                50
                        )
        )
                .isInstanceOf(
                        ResourceNotFoundException.class
                )
                .hasMessageContaining(
                        "База знаний не найдена"
                );

        verifyNoInteractions(
                queryRepository
        );
    }

    @Test
    void listReturnsTenantScopedDocuments() {
        when(
                bases.findByIdAndOrganizationId(
                        KNOWLEDGE_BASE_ID,
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
                                KNOWLEDGE_BASE_ID,
                                ORGANIZATION_ID,
                                USER_ID
                        )
        ).thenReturn(
                Optional.empty()
        );

        KnowledgeDocumentPageResponse emptyPage =
                new KnowledgeDocumentPageResponse(
                        List.of(),
                        0,
                        50,
                        0L,
                        0
                );

        when(
                queryRepository.list(
                        ORGANIZATION_ID,
                        KNOWLEDGE_BASE_ID,
                        0,
                        50,
                        false
                )
        ).thenReturn(
                emptyPage
        );

        var response =
                service.list(
                        KNOWLEDGE_BASE_ID,
                        user(),
                        0,
                        50
                );

        assertThat(
                response.content()
        ).isEmpty();

        verify(
                queryRepository
        ).list(
                ORGANIZATION_ID,
                KNOWLEDGE_BASE_ID,
                0,
                50,
                false
        );
    }

    @Test
    void downloadReturnsCurrentDocumentVersion()
            throws IOException {
        UUID versionId =
                UUID.randomUUID();

        KnowledgeDocumentEntity document =
                existingDocument();

        document.setCurrentVersionId(
                versionId
        );

        KnowledgeDocumentVersionEntity version =
                version(
                        versionId
                );

        StoredObject storedObject =
                new StoredObject(
                        new ByteArrayResource(
                                "document".getBytes(
                                        StandardCharsets.UTF_8
                                )
                        ),
                        8
                );

        stubBaseForAdmin();

        when(
                documents
                        .findByIdAndKnowledgeBaseIdAndOrganizationId(
                                DOCUMENT_ID,
                                KNOWLEDGE_BASE_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(
                        document
                )
        );

        when(
                versions
                        .findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                                versionId,
                                DOCUMENT_ID,
                                KNOWLEDGE_BASE_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(
                        version
                )
        );

        when(
                storage.get(
                        "knowledge/document.txt"
                )
        ).thenReturn(
                storedObject
        );

        var result =
                service.download(
                        KNOWLEDGE_BASE_ID,
                        DOCUMENT_ID,
                        null,
                        admin()
                );

        assertThat(
                result.object()
        ).isSameAs(
                storedObject
        );

        assertThat(
                result.filename()
        ).isEqualTo(
                "runbook.txt"
        );

        assertThat(
                result.mediaType()
        ).isEqualTo(
                "text/plain"
        );

        verify(
                downloadAudit
        ).tryRecord(
                any(),
                eq(
                        ORGANIZATION_ID
                ),
                eq(
                        AuditEventType.KNOWLEDGE_DOCUMENT_DOWNLOADED
                ),
                anyMap()
        );
    }

    private void stubBaseForAdmin() {
        when(
                bases.findByIdAndOrganizationId(
                        KNOWLEDGE_BASE_ID,
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

    private void stubDocumentPersistence() {
        when(
                documents.saveAndFlush(
                        any(KnowledgeDocumentEntity.class)
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(
                                0
                        )
        );
    }

    private void stubVersionPersistence() {
        when(
                versions.saveAndFlush(
                        any(KnowledgeDocumentVersionEntity.class)
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(
                                0
                        )
        );
    }

    private void stubJobPersistence() {
        when(
                jobs.saveAndFlush(
                        any(KnowledgeIngestionJobEntity.class)
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(
                                0
                        )
        );
    }

    private KnowledgeBaseEntity enabledBase(
            KnowledgeBaseVisibility visibility
    ) {
        KnowledgeBaseEntity knowledgeBase =
                new KnowledgeBaseEntity();

        knowledgeBase.setId(
                KNOWLEDGE_BASE_ID
        );

        knowledgeBase.setOrganizationId(
                ORGANIZATION_ID
        );

        knowledgeBase.setName(
                "Knowledge Base"
        );

        knowledgeBase.setVisibility(
                visibility
        );

        knowledgeBase.setEnabled(
                true
        );

        knowledgeBase.setCreatedByUserId(
                USER_ID
        );

        return knowledgeBase;
    }

    private KnowledgeDocumentEntity existingDocument() {
        KnowledgeDocumentEntity document =
                new KnowledgeDocumentEntity();

        document.setId(
                DOCUMENT_ID
        );

        document.setOrganizationId(
                ORGANIZATION_ID
        );

        document.setKnowledgeBaseId(
                KNOWLEDGE_BASE_ID
        );

        document.setName(
                "Runbook"
        );

        document.setEnabled(
                true
        );

        document.setCreatedByUserId(
                USER_ID
        );

        return document;
    }

    private KnowledgeDocumentVersionEntity version(
            UUID versionId
    ) {
        KnowledgeDocumentVersionEntity version =
                new KnowledgeDocumentVersionEntity();

        version.setId(
                versionId
        );

        version.setOrganizationId(
                ORGANIZATION_ID
        );

        version.setKnowledgeBaseId(
                KNOWLEDGE_BASE_ID
        );

        version.setDocumentId(
                DOCUMENT_ID
        );

        version.setVersionNumber(
                1
        );

        version.setOriginalFilename(
                "runbook.txt"
        );

        version.setMediaType(
                "text/plain"
        );

        version.setSizeBytes(
                8
        );

        version.setSha256(
                "a".repeat(
                        64
                )
        );

        version.setStorageKey(
                "knowledge/document.txt"
        );

        version.setCreatedByUserId(
                USER_ID
        );

        return version;
    }

    private MockMultipartFile textFile(
            String filename,
            String content
    ) {
        return new MockMultipartFile(
                "file",
                filename,
                "text/plain",
                content.getBytes(
                        StandardCharsets.UTF_8
                )
        );
    }

    private SafeAiUserPrincipal admin() {
        return principal(
                "ROLE_ADMIN"
        );
    }

    private SafeAiUserPrincipal user() {
        return principal(
                "ROLE_USER"
        );
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
}