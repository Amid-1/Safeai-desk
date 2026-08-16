package ru.safeai.gateway.knowledge.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocumentNameNormalizerTest {

    @Test
    void normalize_collapsesUnicodeWhitespace() {
        assertThat(KnowledgeDocumentNameNormalizer.normalize("  Регламент\u00A0\u00A0SafeAI\u2003 2026  "))
                .isEqualTo("Регламент SafeAI 2026");
    }

    @Test
    void normalize_preservesExtensionWhenNameComesFromFilename() {
        assertThat(KnowledgeDocumentNameNormalizer.normalize("  Инструкция   оператора.pdf  "))
                .isEqualTo("Инструкция оператора.pdf");
    }

    @Test
    void normalize_acceptsExactly255CodePoints() {
        String value = "Д".repeat(255);
        assertThat(KnowledgeDocumentNameNormalizer.normalize(value)).isEqualTo(value);
    }

    @Test
    void normalize_rejectsMoreThan255CodePoints() {
        assertThatThrownBy(() -> KnowledgeDocumentNameNormalizer.normalize("Д".repeat(256)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("255");
    }

    @Test
    void normalize_rejectsControlCharacters() {
        assertThatThrownBy(() -> KnowledgeDocumentNameNormalizer.normalize("Документ\u0000скрытый"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("управляющие символы");
    }

    @Test
    void normalize_rejectsBlank() {
        assertThatThrownBy(() -> KnowledgeDocumentNameNormalizer.normalize(" \u00A0\u2003 "))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("не должно быть пустым");
    }

    @Test
    void normalize_rejectsNull() {
        assertThatThrownBy(() -> KnowledgeDocumentNameNormalizer.normalize(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value");
    }
}
