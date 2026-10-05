package ru.safeai.gateway.knowledge.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentPageResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentVersionPageResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeHealthResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeReindexResponse;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeDocumentRequest;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentService;
import ru.safeai.gateway.knowledge.service.KnowledgeOperationsService;
import ru.safeai.gateway.knowledge.storage.StoredObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentControllerTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000002"
            );

    private static final UUID KB_ID =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000003"
            );

    private static final UUID DOCUMENT_ID =
            UUID.fromString(
                    "40000000-0000-0000-0000-000000000004"
            );

    private static final UUID VERSION_ID =
            UUID.fromString(
                    "50000000-0000-0000-0000-000000000005"
            );

    @Mock
    KnowledgeDocumentService service;

    @Mock
    KnowledgeOperationsService operations;

    KnowledgeDocumentController controller;

    @BeforeEach
    void setUp() {
        controller =
                new KnowledgeDocumentController(
                        service,
                        operations
                );
    }

    @Test
    void health_delegatesKnowledgeBaseAndPrincipal() {
        SafeAiUserPrincipal principal =
                principal();

        KnowledgeHealthResponse expected =
                mock(
                        KnowledgeHealthResponse.class
                );

        when(
                operations.health(
                        KB_ID,
                        principal
                )
        ).thenReturn(
                expected
        );

        assertThat(
                controller.health(
                        KB_ID,
                        principal
                )
        ).isSameAs(
                expected
        );

        verify(
                operations
        ).health(
                KB_ID,
                principal
        );
    }

    @Test
    void reindex_delegatesDocumentAndPrincipal() {
        SafeAiUserPrincipal principal =
                principal();

        KnowledgeReindexResponse expected =
                mock(
                        KnowledgeReindexResponse.class
                );

        when(
                operations.reindex(
                        KB_ID,
                        DOCUMENT_ID,
                        principal
                )
        ).thenReturn(
                expected
        );

        assertThat(
                controller.reindex(
                        KB_ID,
                        DOCUMENT_ID,
                        principal
                )
        ).isSameAs(
                expected
        );

        verify(
                operations
        ).reindex(
                KB_ID,
                DOCUMENT_ID,
                principal
        );
    }

    @Test
    void list_delegatesPaginationAndPrincipal() {
        SafeAiUserPrincipal principal =
                principal();

        KnowledgeDocumentPageResponse expected =
                new KnowledgeDocumentPageResponse(
                        List.of(),
                        2,
                        25,
                        0,
                        0
                );

        when(
                service.list(
                        KB_ID,
                        principal,
                        2,
                        25
                )
        ).thenReturn(
                expected
        );

        assertThat(
                controller.list(
                        KB_ID,
                        2,
                        25,
                        principal
                )
        ).isSameAs(
                expected
        );
    }

    @Test
    void listVersions_delegatesPaginationDocumentAndPrincipal() {
        SafeAiUserPrincipal principal =
                principal();

        KnowledgeDocumentVersionPageResponse expected =
                mock(
                        KnowledgeDocumentVersionPageResponse.class
                );

        when(
                service.listVersions(
                        KB_ID,
                        DOCUMENT_ID,
                        principal,
                        1,
                        10
                )
        ).thenReturn(
                expected
        );

        assertThat(
                controller.listVersions(
                        KB_ID,
                        DOCUMENT_ID,
                        1,
                        10,
                        principal
                )
        ).isSameAs(
                expected
        );
    }

    @Test
    void update_delegatesOptimisticVersionRequestAndPrincipal() {
        SafeAiUserPrincipal principal =
                principal();

        UpdateKnowledgeDocumentRequest request =
                new UpdateKnowledgeDocumentRequest(
                        "Updated Runbook",
                        true,
                        7L
                );

        KnowledgeDocumentResponse expected =
                mock(
                        KnowledgeDocumentResponse.class
                );

        when(
                service.update(
                        KB_ID,
                        DOCUMENT_ID,
                        request,
                        principal
                )
        ).thenReturn(
                expected
        );

        assertThat(
                controller.update(
                        KB_ID,
                        DOCUMENT_ID,
                        request,
                        principal
                )
        ).isSameAs(
                expected
        );
    }

    @Test
    void upload_delegatesMultipartFileAndOptionalName() {
        SafeAiUserPrincipal principal =
                principal();

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "данные.txt",
                        "text/plain",
                        "text".getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        KnowledgeDocumentResponse expected =
                mock(
                        KnowledgeDocumentResponse.class
                );

        when(
                service.uploadNew(
                        KB_ID,
                        "Регламент",
                        file,
                        principal
                )
        ).thenReturn(
                expected
        );

        assertThat(
                controller.upload(
                        KB_ID,
                        file,
                        "Регламент",
                        principal
                )
        ).isSameAs(
                expected
        );
    }

    @Test
    void uploadVersion_delegatesExistingDocumentAndPrincipal() {
        SafeAiUserPrincipal principal =
                principal();

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "version-2.txt",
                        "text/plain",
                        "v2".getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        KnowledgeDocumentResponse expected =
                mock(
                        KnowledgeDocumentResponse.class
                );

        when(
                service.uploadVersion(
                        KB_ID,
                        DOCUMENT_ID,
                        file,
                        principal
                )
        ).thenReturn(
                expected
        );

        assertThat(
                controller.uploadVersion(
                        KB_ID,
                        DOCUMENT_ID,
                        file,
                        principal
                )
        ).isSameAs(
                expected
        );
    }

    @Test
    void downloadCurrent_setsSafeHeadersAndUnicodeFilename() {
        SafeAiUserPrincipal principal =
                principal();

        byte[] bytes =
                "данные".getBytes(
                        StandardCharsets.UTF_8
                );

        StoredObject object =
                new StoredObject(
                        new ByteArrayResource(
                                bytes
                        ),
                        bytes.length
                );

        when(
                service.download(
                        KB_ID,
                        DOCUMENT_ID,
                        null,
                        principal
                )
        ).thenReturn(
                new KnowledgeDocumentService.Download(
                        object,
                        "Регламент №1.txt",
                        "text/plain"
                )
        );

        var response =
                controller.downloadCurrent(
                        KB_ID,
                        DOCUMENT_ID,
                        principal
                );

        assertThat(
                response.getStatusCode()
                        .is2xxSuccessful()
        ).isTrue();

        assertThat(
                response.getHeaders()
                        .getContentType()
        ).isEqualTo(
                MediaType.TEXT_PLAIN
        );

        assertThat(
                response.getHeaders()
                        .getContentLength()
        ).isEqualTo(
                bytes.length
        );

        assertThat(
                response.getHeaders()
                        .getFirst(
                                "X-Content-Type-Options"
                        )
        ).isEqualTo(
                "nosniff"
        );

        assertThat(
                response.getHeaders()
                        .getFirst(
                                HttpHeaders.CONTENT_DISPOSITION
                        )
        )
                .startsWith(
                        "attachment"
                )
                .contains(
                        "filename"
                );

        assertThat(
                response.getBody()
        ).isSameAs(
                object.resource()
        );
    }

    @Test
    void downloadVersion_passesExactVersionIdAndUsesSameSafeResponseBoundary() {
        SafeAiUserPrincipal principal =
                principal();

        byte[] bytes =
                new byte[]{1, 2, 3};

        StoredObject object =
                new StoredObject(
                        new ByteArrayResource(
                                bytes
                        ),
                        bytes.length
                );

        when(
                service.download(
                        KB_ID,
                        DOCUMENT_ID,
                        VERSION_ID,
                        principal
                )
        ).thenReturn(
                new KnowledgeDocumentService.Download(
                        object,
                        "version.bin",
                        "application/octet-stream"
                )
        );

        var response =
                controller.downloadVersion(
                        KB_ID,
                        DOCUMENT_ID,
                        VERSION_ID,
                        principal
                );

        assertThat(
                response.getHeaders()
                        .getContentType()
        ).isEqualTo(
                MediaType.APPLICATION_OCTET_STREAM
        );

        assertThat(
                response.getHeaders()
                        .getFirst(
                                "X-Content-Type-Options"
                        )
        ).isEqualTo(
                "nosniff"
        );

        assertThat(
                response.getBody()
        ).isSameAs(
                object.resource()
        );

        verify(
                service
        ).download(
                KB_ID,
                DOCUMENT_ID,
                VERSION_ID,
                principal
        );
    }

    @Test
    void downloadCurrent_invalidStoredMediaTypeFallsBackToOctetStream() {
        SafeAiUserPrincipal principal =
                principal();

        StoredObject object =
                new StoredObject(
                        new ByteArrayResource(
                                new byte[]{1}
                        ),
                        1
                );

        when(
                service.download(
                        KB_ID,
                        DOCUMENT_ID,
                        null,
                        principal
                )
        ).thenReturn(
                new KnowledgeDocumentService.Download(
                        object,
                        "file.bin",
                        "broken media/type; ="
                )
        );

        var response =
                controller.downloadCurrent(
                        KB_ID,
                        DOCUMENT_ID,
                        principal
                );

        assertThat(
                response.getHeaders()
                        .getContentType()
        ).isEqualTo(
                MediaType.APPLICATION_OCTET_STREAM
        );
    }

    private static SafeAiUserPrincipal principal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                0L,
                0L,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_ADMIN"
                        )
                )
        );
    }
}
