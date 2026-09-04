package ru.safeai.gateway.knowledge.config;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageProperties;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageType;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeSecretPropertiesTest {

    @Test
    void secretBearingPropertiesRedactToString() {
        String embeddingSecret = "embedding-secret-value";
        String ocrSecret = "ocr-secret-value";
        String accessKey = "storage-access-key";
        String secretKey = "storage-secret-key";

        KnowledgeEmbeddingProperties embedding =
                new KnowledgeEmbeddingProperties(
                        "openai",
                        "https://api.openai.com/v1",
                        embeddingSecret,
                        "text-embedding-3-small",
                        384,
                        64,
                        20_000,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30)
                );

        KnowledgeOcrProperties ocr =
                new KnowledgeOcrProperties(
                        "http",
                        "https://ocr.safeai.test/v1/extract",
                        ocrSecret,
                        List.of("ocr.safeai.test"),
                        20,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30),
                        1024L * 1024L
                );

        KnowledgeStorageProperties storage =
                new KnowledgeStorageProperties(
                        KnowledgeStorageType.S3,
                        Path.of("./unused"),
                        25L * 1024L * 1024L,
                        "https://s3.safeai.test",
                        accessKey,
                        secretKey,
                        "safeai-knowledge"
                );

        assertThat(embedding.toString())
                .doesNotContain(embeddingSecret)
                .contains("<redacted>");
        assertThat(ocr.toString())
                .doesNotContain(ocrSecret)
                .contains("<redacted>");
        assertThat(storage.toString())
                .doesNotContain(accessKey, secretKey)
                .contains("<redacted>");
    }
}
