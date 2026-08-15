package ru.safeai.gateway.knowledge.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocumentServiceTest {

    @Test
    void detectsPdfByContentInsteadOfClientHeader() {
        assertThat(KnowledgeDocumentService.detectType(
                "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII)
        )).isEqualTo("application/pdf");
    }

    @Test
    void detectsHtmlAndPlainText() {
        assertThat(KnowledgeDocumentService.detectType(
                "<!doctype html><html></html>".getBytes(StandardCharsets.UTF_8)
        )).isEqualTo("text/html");

        assertThat(KnowledgeDocumentService.detectType(
                "Обычный текст".getBytes(StandardCharsets.UTF_8)
        )).isEqualTo("text/plain");
    }

    @Test
    void rejectsUnsupportedBinaryPayload() {
        assertThatThrownBy(() -> KnowledgeDocumentService.detectType(
                new byte[]{0, 1, 2, 3, 4}
        )).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("PDF, DOCX, TXT и HTML");
    }

    @Test
    void doesNotTrustZipSignatureAsDocx() {
        assertThatThrownBy(() -> KnowledgeDocumentService.detectType(
                new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0}
        )).isInstanceOf(BadRequestException.class);
    }
}
