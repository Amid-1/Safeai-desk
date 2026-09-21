package ru.safeai.gateway.knowledge.storage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class KnowledgeStorageProductionInvariantVerifierTest {
    @Test
    void productionRejectsLocalAndInsecureS3() {
        var local = new KnowledgeStorageProperties(KnowledgeStorageType.LOCAL, Path.of("./tmp"),
                null, null, null, null, null);
        var insecure = new KnowledgeStorageProperties(KnowledgeStorageType.S3, null,
                null, "http://localhost:9000", "key", "secret", "safeai-knowledge");
        assertThatThrownBy(new KnowledgeStorageProductionInvariantVerifier(local)::verify)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(new KnowledgeStorageProductionInvariantVerifier(insecure)::verify)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
    }

    @Test
    void productionAcceptsHttpsS3() {
        var secure = new KnowledgeStorageProperties(KnowledgeStorageType.S3, null,
                null, "https://s3.safeai.test", "key", "secret", "safeai-knowledge");
        assertThatCode(new KnowledgeStorageProductionInvariantVerifier(secure)::verify)
                .doesNotThrowAnyException();
    }
}
