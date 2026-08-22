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
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentService;
import ru.safeai.gateway.knowledge.service.KnowledgeOperationsService;
import ru.safeai.gateway.knowledge.storage.StoredObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentControllerTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID KB_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID DOCUMENT_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");

    @Mock KnowledgeDocumentService service;
    @Mock KnowledgeOperationsService operations;
    KnowledgeDocumentController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeDocumentController(service, operations);
    }

    @Test
    void list_delegatesPaginationAndPrincipal() {
        SafeAiUserPrincipal principal = principal();
        KnowledgeDocumentPageResponse expected = new KnowledgeDocumentPageResponse(List.of(), 2, 25, 0, 0);
        when(service.list(KB_ID, principal, 2, 25)).thenReturn(expected);

        assertThat(controller.list(KB_ID, 2, 25, principal)).isSameAs(expected);
    }

    @Test
    void upload_delegatesMultipartFileAndOptionalName() {
        SafeAiUserPrincipal principal = principal();
        MockMultipartFile file = new MockMultipartFile(
                "file", "данные.txt", "text/plain", "text".getBytes(StandardCharsets.UTF_8)
        );
        KnowledgeDocumentResponse expected = mock(KnowledgeDocumentResponse.class);
        when(service.uploadNew(KB_ID, "Регламент", file, principal)).thenReturn(expected);

        assertThat(controller.upload(KB_ID, file, "Регламент", principal)).isSameAs(expected);
    }

    @Test
    void downloadCurrent_setsSafeHeadersAndUnicodeFilename() {
        SafeAiUserPrincipal principal = principal();
        byte[] bytes = "данные".getBytes(StandardCharsets.UTF_8);
        StoredObject object = new StoredObject(new ByteArrayResource(bytes), bytes.length);
        when(service.download(KB_ID, DOCUMENT_ID, null, principal)).thenReturn(
                new KnowledgeDocumentService.Download(object, "Регламент №1.txt", "text/plain")
        );

        var response = controller.downloadCurrent(KB_ID, DOCUMENT_ID, principal);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(bytes.length);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment")
                .contains("filename");
        assertThat(response.getBody()).isSameAs(object.resource());
    }

    @Test
    void downloadCurrent_invalidStoredMediaTypeFallsBackToOctetStream() {
        SafeAiUserPrincipal principal = principal();
        StoredObject object = new StoredObject(new ByteArrayResource(new byte[]{1}), 1);
        when(service.download(KB_ID, DOCUMENT_ID, null, principal)).thenReturn(
                new KnowledgeDocumentService.Download(object, "file.bin", "broken media/type; =")
        );

        var response = controller.downloadCurrent(KB_ID, DOCUMENT_ID, principal);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    }

    private SafeAiUserPrincipal principal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                0L,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }
}
