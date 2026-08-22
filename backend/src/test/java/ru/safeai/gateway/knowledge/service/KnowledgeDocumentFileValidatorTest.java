package ru.safeai.gateway.knowledge.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageProperties;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDocumentFileValidatorTest {

    private static final long DEFAULT_MAX_UPLOAD_BYTES =
            25L * 1024L * 1024L;

    private static final long MIN_VALID_UPLOAD_LIMIT =
            1024L * 1024L;

    private static final String TEST_BUCKET =
            "safeai-knowledge";

    @TempDir
    Path tempDir;

    @Test
    void validate_preservesExistingPdfDocxTxtAndHtmlSupport()
            throws IOException {
        assertMediaType(
                "notes.txt",
                "Привет, SafeAI!\nLine 2\n"
                        .getBytes(
                                StandardCharsets.UTF_8
                        ),
                "application/x-client-lie",
                KnowledgeDocumentFileValidator.TEXT_MEDIA_TYPE
        );

        assertMediaType(
                "page.html",
                "\uFEFF<!doctype html><html><body>OK</body></html>"
                        .getBytes(
                                StandardCharsets.UTF_8
                        ),
                "application/octet-stream",
                KnowledgeDocumentFileValidator.HTML_MEDIA_TYPE
        );

        assertMediaType(
                "architecture.pdf",
                validPdf(),
                "text/plain",
                KnowledgeDocumentFileValidator.PDF_MEDIA_TYPE
        );

        assertMediaType(
                "document.docx",
                minimalOoxml(
                        "word/document.xml",
                        "/word/document.xml",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                ),
                "application/zip",
                KnowledgeDocumentFileValidator.DOCX_MEDIA_TYPE
        );
    }

    @Test
    void validate_acceptsMarkdownCsvJsonAndXml() {
        assertMediaType(
                "README.md",
                """
                # SafeAI

                Production runbook.

                - health
                - rollback
                """.getBytes(
                        StandardCharsets.UTF_8
                ),
                "text/plain",
                KnowledgeDocumentFileValidator.MARKDOWN_MEDIA_TYPE
        );

        assertMediaType(
                "catalog.csv",
                """
                id,name,status
                1,SafeAI,ACTIVE
                2,Gateway,READY
                """.getBytes(
                        StandardCharsets.UTF_8
                ),
                "application/octet-stream",
                KnowledgeDocumentFileValidator.CSV_MEDIA_TYPE
        );

        assertMediaType(
                "settings.json",
                """
                {
                  "service": "safeai",
                  "enabled": true,
                  "limits": [10, 20, 30]
                }
                """.getBytes(
                        StandardCharsets.UTF_8
                ),
                "text/plain",
                KnowledgeDocumentFileValidator.JSON_MEDIA_TYPE
        );

        assertMediaType(
                "integration.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <integration>
                  <name>SafeAI</name>
                  <enabled>true</enabled>
                </integration>
                """.getBytes(
                        StandardCharsets.UTF_8
                ),
                "text/plain",
                KnowledgeDocumentFileValidator.XML_MEDIA_TYPE
        );
    }

    @Test
    void validate_acceptsRealOoxmlShapeForXlsxAndPptx()
            throws IOException {
        assertMediaType(
                "catalog.xlsx",
                minimalOoxml(
                        "xl/workbook.xml",
                        "/xl/workbook.xml",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
                ),
                "application/zip",
                KnowledgeDocumentFileValidator.XLSX_MEDIA_TYPE
        );

        assertMediaType(
                "architecture.pptx",
                minimalOoxml(
                        "ppt/presentation.xml",
                        "/ppt/presentation.xml",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
                ),
                "application/zip",
                KnowledgeDocumentFileValidator.PPTX_MEDIA_TYPE
        );
    }

    @Test
    void validate_rejectsInvalidJsonAndMalformedXml() {
        assertThatThrownBy(
                () -> validate(
                        "broken.json",
                        """
                        {"name":"SafeAI",}
                        """.getBytes(
                                StandardCharsets.UTF_8
                        )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "JSON"
                );

        assertThatThrownBy(
                () -> validate(
                        "broken.xml",
                        """
                        <root><item></root>
                        """.getBytes(
                                StandardCharsets.UTF_8
                        )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "XML"
                );
    }

    @Test
    void validate_rejectsXmlDoctypeAndEntities() {
        byte[] dangerousXml =
                """
                <?xml version="1.0"?>
                <!DOCTYPE root [
                  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <root>&xxe;</root>
                """.getBytes(
                        StandardCharsets.UTF_8
                );

        assertThatThrownBy(
                () -> validate(
                        "dangerous.xml",
                        dangerousXml
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "XML"
                );
    }

    @Test
    void validate_rejectsOoxmlExtensionContentMismatch()
            throws IOException {
        byte[] xlsx =
                minimalOoxml(
                        "xl/workbook.xml",
                        "/xl/workbook.xml",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
                );

        assertThatThrownBy(
                () -> validate(
                        "not-a-document.docx",
                        xlsx
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "Расширение файла"
                );
    }

    @Test
    void validate_rejectsFakePdfAndUnsupportedLegacyOfficeFormat() {
        assertThatThrownBy(
                () -> validate(
                        "fake.pdf",
                        "обычный текст"
                                .getBytes(
                                        StandardCharsets.UTF_8
                                )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "Расширение файла"
                );

        assertThatThrownBy(
                () -> validate(
                        "legacy.doc",
                        "legacy"
                                .getBytes(
                                        StandardCharsets.UTF_8
                                )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "PDF, DOCX, TXT, HTML, MD, CSV, XLSX, PPTX, JSON и XML"
                );
    }

    @Test
    void validate_jsonNumberAndEscapeGrammarIsStrict() {
        assertMediaType(
                "valid.json",
                """
                {"value":-12.50e+2,"escaped":"line\\n\\u0410"}
                """.getBytes(
                        StandardCharsets.UTF_8
                ),
                "application/json",
                KnowledgeDocumentFileValidator.JSON_MEDIA_TYPE
        );

        assertThatThrownBy(
                () -> validate(
                        "leading-zero.json",
                        "{\"value\":01}"
                                .getBytes(
                                        StandardCharsets.UTF_8
                                )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "JSON"
                );

        assertThatThrownBy(
                () -> validate(
                        "two-roots.json",
                        "{} {}"
                                .getBytes(
                                        StandardCharsets.UTF_8
                                )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "JSON"
                );
    }

    @Test
    void validate_ignoresClientMimeAndReturnsStableSha256() {
        byte[] bytes =
                "# SafeAI\n"
                        .getBytes(
                                StandardCharsets.UTF_8
                        );

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "README.md",
                        "application/x-client-lie",
                        bytes
                );

        var result =
                validator().validate(
                        file
                );

        assertThat(
                result.originalFilename()
        )
                .isEqualTo(
                        "README.md"
                );

        assertThat(
                result.mediaType()
        )
                .isEqualTo(
                        KnowledgeDocumentFileValidator.MARKDOWN_MEDIA_TYPE
                );

        assertThat(
                result.bytes()
        )
                .containsExactly(
                        bytes
                );

        assertThat(
                result.sha256()
        )
                .isEqualTo(
                        sha256(
                                bytes
                        )
                );
    }

    @Test
    void validate_rejectsInvalidUtf8BinaryAndDisallowedControls() {
        assertThatThrownBy(
                () -> validate(
                        "payload.txt",
                        new byte[]{
                                (byte) 0xC3,
                                (byte) 0x28,
                                0x00,
                                0x01
                        }
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                );

        assertThatThrownBy(
                () -> validate(
                        "payload.md",
                        "hello\u0000world"
                                .getBytes(
                                        StandardCharsets.UTF_8
                                )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                );
    }

    @Test
    void validate_sanitizesFilenameAndRejectsBadNames() {
        MockMultipartFile pathFile =
                new MockMultipartFile(
                        "file",
                        "C:\\fakepath\\folder\\notes.txt",
                        "text/plain",
                        "text"
                                .getBytes(
                                        StandardCharsets.UTF_8
                                )
                );

        assertThat(
                validator()
                        .validate(
                                pathFile
                        )
                        .originalFilename()
        )
                .isEqualTo(
                        "notes.txt"
                );

        assertThatThrownBy(
                () -> validator().validate(
                        new MockMultipartFile(
                                "file",
                                "",
                                "text/plain",
                                "text"
                                        .getBytes(
                                                StandardCharsets.UTF_8
                                        )
                        )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "имя файла"
                );

        assertThatThrownBy(
                () -> validator().validate(
                        new MockMultipartFile(
                                "file",
                                "bad\u0000.txt",
                                "text/plain",
                                "text"
                                        .getBytes(
                                                StandardCharsets.UTF_8
                                        )
                        )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "управляющие"
                );

        String tooLong =
                "я".repeat(
                        252
                )
                        + ".txt";

        assertThatThrownBy(
                () -> validator().validate(
                        new MockMultipartFile(
                                "file",
                                tooLong,
                                "text/plain",
                                "text"
                                        .getBytes(
                                                StandardCharsets.UTF_8
                                        )
                        )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "255"
                );
    }

    @Test
    void validate_enforcesSizeBeforeAndAfterRead()
            throws IOException {
        KnowledgeDocumentFileValidator validator =
                validatorWithLimit(
                        MIN_VALID_UPLOAD_LIMIT
                );

        MultipartFile reportedTooLarge =
                mock(
                        MultipartFile.class
                );

        when(
                reportedTooLarge.isEmpty()
        )
                .thenReturn(
                        false
                );

        when(
                reportedTooLarge.getSize()
        )
                .thenReturn(
                        MIN_VALID_UPLOAD_LIMIT + 1L
                );

        assertThatThrownBy(
                () -> validator.validate(
                        reportedTooLarge
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        MIN_VALID_UPLOAD_LIMIT
                                + " байт"
                );

        verify(
                reportedTooLarge,
                never()
        )
                .getBytes();

        MultipartFile actualTooLarge =
                mock(
                        MultipartFile.class
                );

        when(
                actualTooLarge.isEmpty()
        )
                .thenReturn(
                        false
                );

        when(
                actualTooLarge.getSize()
        )
                .thenReturn(
                        MIN_VALID_UPLOAD_LIMIT
                );

        when(
                actualTooLarge.getOriginalFilename()
        )
                .thenReturn(
                        "a.txt"
                );

        byte[] bytesExceedingConfiguredLimit =
                new byte[
                        Math.toIntExact(
                                MIN_VALID_UPLOAD_LIMIT + 1L
                        )
                ];

        when(
                actualTooLarge.getBytes()
        )
                .thenReturn(
                        bytesExceedingConfiguredLimit
                );

        assertThatThrownBy(
                () -> validator.validate(
                        actualTooLarge
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        MIN_VALID_UPLOAD_LIMIT
                                + " байт"
                );
    }

    @Test
    void validate_wrapsMultipartReadFailure()
            throws IOException {
        MultipartFile file =
                mock(
                        MultipartFile.class
                );

        when(
                file.isEmpty()
        )
                .thenReturn(
                        false
                );

        when(
                file.getSize()
        )
                .thenReturn(
                        10L
                );

        when(
                file.getOriginalFilename()
        )
                .thenReturn(
                        "a.txt"
                );

        when(
                file.getBytes()
        )
                .thenThrow(
                        new IOException(
                                "boom"
                        )
                );

        assertThatThrownBy(
                () -> validator().validate(
                        file
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "Не удалось прочитать"
                );
    }

    private void assertMediaType(
            String filename,
            byte[] bytes,
            String clientMediaType,
            String expectedMediaType
    ) {
        var result =
                validator().validate(
                        new MockMultipartFile(
                                "file",
                                filename,
                                clientMediaType,
                                bytes
                        )
                );

        assertThat(
                result.mediaType()
        )
                .isEqualTo(
                        expectedMediaType
                );
    }

    private void validate(
            String filename,
            byte[] bytes
    ) {
        validator().validate(
                new MockMultipartFile(
                        "file",
                        filename,
                        "application/octet-stream",
                        bytes
                )
        );
    }

    private KnowledgeDocumentFileValidator validator() {
        return validatorWithLimit(
                DEFAULT_MAX_UPLOAD_BYTES
        );
    }

    private KnowledgeDocumentFileValidator validatorWithLimit(
            long maxUploadBytes
    ) {
        return new KnowledgeDocumentFileValidator(
                new KnowledgeStorageProperties(
                        KnowledgeStorageType.LOCAL,
                        tempDir,
                        maxUploadBytes,
                        null,
                        null,
                        null,
                        TEST_BUCKET
                )
        );
    }

    private static byte[] validPdf() {
        return """
                %PDF-1.7
                1 0 obj
                << /Type /Catalog >>
                endobj
                trailer
                <<>>
                %%EOF
                """
                .getBytes(
                        StandardCharsets.US_ASCII
                );
    }

    @SuppressWarnings("HttpUrlsUsage")
    private static byte[] minimalOoxml(
            String requiredEntry,
            String partName,
            String contentType
    ) throws IOException {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        try (
                ZipOutputStream zip =
                        new ZipOutputStream(
                                output
                        )
        ) {
            zip.putNextEntry(
                    new ZipEntry(
                            "[Content_Types].xml"
                    )
            );

            zip.write(
                    (
                            """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                              <Override PartName="%s" ContentType="%s"/>
                            </Types>
                            """
                    )
                            .formatted(
                                    partName,
                                    contentType
                            )
                            .getBytes(
                                    StandardCharsets.UTF_8
                            )
            );

            zip.closeEntry();

            zip.putNextEntry(
                    new ZipEntry(
                            requiredEntry
                    )
            );

            zip.write(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <root/>
                    """
                            .getBytes(
                                    StandardCharsets.UTF_8
                            )
            );

            zip.closeEntry();
        }

        return output.toByteArray();
    }

    private static String sha256(
            byte[] bytes
    ) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest
                                    .getInstance(
                                            "SHA-256"
                                    )
                                    .digest(
                                            bytes
                                    )
                    );
        } catch (Exception exception) {
            throw new AssertionError(
                    exception
            );
        }
    }
}
