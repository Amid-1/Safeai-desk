package ru.safeai.gateway.knowledge.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseNameNormalizerTest {

    @Test
    void normalize_collapsesWhitespace() {
        assertThat(
                KnowledgeBaseNameNormalizer.normalize(
                        "  IT   Production   Runbooks  "
                )
        ).isEqualTo("IT Production Runbooks");
    }

    @Test
    void normalize_rejectsControls() {
        assertThatThrownBy(
                () -> KnowledgeBaseNameNormalizer.normalize(
                        "IT\u0000Runbooks"
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("управляющие символы");
    }

    @Test
    void normalize_rejectsBlank() {
        assertThatThrownBy(
                () -> KnowledgeBaseNameNormalizer.normalize("   ")
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("не должно быть пустым");
    }
}
