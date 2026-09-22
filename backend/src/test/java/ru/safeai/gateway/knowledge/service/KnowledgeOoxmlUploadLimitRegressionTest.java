package ru.safeai.gateway.knowledge.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.extraction.OoxmlPackageSupport;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeOoxmlUploadLimitRegressionTest {
    /**
     * This OOXML namespace is a standardized XML identifier, NOT a URL used
     * for network communication. Replacing http with https changes the
     * namespace identity and makes this DOCX test invalid.
     * Only suppress the URL inspection for this fixture field.
     */
    @SuppressWarnings("HttpUrlsUsage")
    private static final String TYPES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Override PartName="/word/document.xml"
                ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
            """;

    @Test
    void smallValidDocxIsRecognizedByTheSharedBoundedScanner() throws IOException {
        byte[] bytes = document("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(new KnowledgeOoxmlDetector(10_000L).detect(bytes))
                .isEqualTo(KnowledgeDocumentMediaTypes.DOCX);
        OoxmlPackageSupport.validate(bytes, "word/document.xml", 10_000L, "DOCX");
    }

    @Test
    void tinyCompressedInputWithExcessiveInflationIsNeverAcceptedAtUpload() throws IOException {
        byte[] inflated = new byte[32_768];
        java.util.Arrays.fill(inflated, (byte) 'x');
        byte[] bytes = document(inflated);
        assertThat(bytes.length).isLessThan(inflated.length);
        assertThat(new KnowledgeOoxmlDetector(4_096L).detect(bytes)).isNull();
        assertThatThrownBy(() ->
                OoxmlPackageSupport.validate(bytes, "word/document.xml", 4_096L, "DOCX"))
                .isInstanceOf(KnowledgeIngestionException.class)
                .extracting("code").isEqualTo("INVALID_DOCX_ARCHIVE");
    }

    private static byte[] document(byte[] content) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(TYPES.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(content);
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }
}
