package ru.safeai.gateway.knowledge.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseNameNormalizerTest {

    @Test
    void normalize_collapsesAsciiAndUnicodeWhitespace() {
        assertThat(KnowledgeBaseNameNormalizer.normalize("  IT\u00A0\u00A0Production   Runbooks \u2003 "))
                .isEqualTo("IT Production Runbooks");
    }

    @Test
    void normalize_preservesOrdinaryUnicodeCharacters() {
        assertThat(KnowledgeBaseNameNormalizer.normalize("  База знаний — Финансы 2026  "))
                .isEqualTo("База знаний — Финансы 2026");
    }

    @Test
    void normalize_acceptsExactly255UnicodeCodePoints() {
        String value = "Я".repeat(255);
        assertThat(KnowledgeBaseNameNormalizer.normalize(value)).isEqualTo(value);
    }

    @Test
    void normalize_rejectsMoreThan255UnicodeCodePoints() {
        assertThatThrownBy(() -> KnowledgeBaseNameNormalizer.normalize("Я".repeat(256)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("255");
    }

    @Test
    void normalize_countsSupplementaryUnicodeByCodePoint() {
        String value = "😀".repeat(255);
        assertThat(KnowledgeBaseNameNormalizer.normalize(value)).isEqualTo(value);
    }

    @Test
    void normalize_rejectsControls() {
        assertThatThrownBy(() -> KnowledgeBaseNameNormalizer.normalize("IT\u0000Runbooks"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("управляющие символы");
    }

    @Test
    void normalize_rejectsNewLineAndTabAsControls() {
        assertThatThrownBy(() -> KnowledgeBaseNameNormalizer.normalize("IT\nRunbooks"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> KnowledgeBaseNameNormalizer.normalize("IT\tRunbooks"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void normalize_rejectsBlank() {
        assertThatThrownBy(() -> KnowledgeBaseNameNormalizer.normalize(" \u00A0\u2003 "))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("не должно быть пустым");
    }

    @Test
    void normalize_rejectsNull() {
        assertThatThrownBy(() -> KnowledgeBaseNameNormalizer.normalize(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value");
    }
}
