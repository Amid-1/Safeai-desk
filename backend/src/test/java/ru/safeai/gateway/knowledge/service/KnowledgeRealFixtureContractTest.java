package ru.safeai.gateway.knowledge.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageProperties;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageType;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeRealFixtureContractTest {

    private static final long MAX_UPLOAD_BYTES =
            26_214_400L;

    private static final String TEST_BUCKET =
            "safeai-knowledge";

    private static final String OCTET_STREAM =
            "application/octet-stream";

    @TempDir
    Path tempDir;

    @Test
    void productionLikeFixtures_areRecognizedByActualBytes()
            throws IOException {
        KnowledgeDocumentFileValidator validator =
                validator();

        assertThat(
                validate(
                        validator,
                        "knowledge-fixtures/positive/architecture.txt",
                        "architecture.txt"
                ).mediaType()
        ).isEqualTo("text/plain");

        assertThat(
                validate(
                        validator,
                        "knowledge-fixtures/positive/security.html",
                        "security.html"
                ).mediaType()
        ).isEqualTo("text/html");

        assertThat(
                validate(
                        validator,
                        "knowledge-fixtures/positive/bom.html",
                        "bom.html"
                ).mediaType()
        ).isEqualTo("text/html");

        assertThat(
                validate(
                        validator,
                        "knowledge-fixtures/positive/operator.docx",
                        "operator.docx"
                ).mediaType()
        ).isEqualTo(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );

        assertThat(
                validate(
                        validator,
                        "knowledge-fixtures/positive/architecture.pdf",
                        "architecture.pdf"
                ).mediaType()
        ).isEqualTo("application/pdf");
    }

    @Test
    void productionLikeNegativeFixtures_areRejected()
            throws IOException {
        KnowledgeDocumentFileValidator validator =
                validator();

        assertRejected(
                validator,
                "knowledge-fixtures/negative/truncated.pdf",
                "truncated.pdf"
        );

        assertRejected(
                validator,
                "knowledge-fixtures/negative/text-disguised.pdf",
                "text-disguised.pdf"
        );

        assertRejected(
                validator,
                "knowledge-fixtures/negative/fake.docx",
                "fake.docx"
        );

        assertRejected(
                validator,
                "knowledge-fixtures/negative/invalid-utf8.txt",
                "invalid-utf8.txt"
        );
    }

    @Test
    void fixtureHash_isStableAndCanBeUsedForRegressionDetection()
            throws IOException {
        KnowledgeDocumentFileValidator.ValidatedUpload upload =
                validate(
                        validator(),
                        "knowledge-fixtures/positive/architecture.pdf",
                        "architecture.pdf"
                );

        assertThat(upload.sha256())
                .matches("^[0-9a-f]{64}$");

        assertThat(upload.bytes().length)
                .isGreaterThan(10_000);
    }

    private KnowledgeDocumentFileValidator.ValidatedUpload validate(
            KnowledgeDocumentFileValidator validator,
            String classpath,
            String filename
    ) throws IOException {
        byte[] bytes =
                readFixture(classpath);

        return validator.validate(
                new MockMultipartFile(
                        "file",
                        filename,
                        OCTET_STREAM,
                        bytes
                )
        );
    }

    private void assertRejected(
            KnowledgeDocumentFileValidator validator,
            String classpath,
            String filename
    ) throws IOException {
        byte[] bytes =
                readFixture(classpath);

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        filename,
                        OCTET_STREAM,
                        bytes
                );

        assertThatThrownBy(
                () -> validator.validate(file)
        ).isInstanceOf(
                BadRequestException.class
        );
    }

    private static byte[] readFixture(
            String classpath
    ) throws IOException {
        return new ClassPathResource(
                classpath
        ).getContentAsByteArray();
    }

    private KnowledgeDocumentFileValidator validator() {
        return new KnowledgeDocumentFileValidator(
                new KnowledgeStorageProperties(
                        KnowledgeStorageType.LOCAL,
                        tempDir,
                        MAX_UPLOAD_BYTES,
                        null,
                        null,
                        null,
                        TEST_BUCKET
                )
        );
    }
}