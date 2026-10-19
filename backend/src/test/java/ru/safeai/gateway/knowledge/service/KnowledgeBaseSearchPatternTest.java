package ru.safeai.gateway.knowledge.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeBaseSearchPatternTest {

    @Test
    void emptyInputMeansNoNameFilter() {
        assertNull(KnowledgeBaseSearchPattern.from(null));
        assertNull(KnowledgeBaseSearchPattern.from("  "));
    }

    @Test
    void literalWildcardsAndEscapeAreEscaped() {
        assertEquals("%100!%!_!!%", KnowledgeBaseSearchPattern.from("100%_!"));
    }

    @Test
    void unicodeSearchIsAccepted() {
        assertEquals("%база 📚%", KnowledgeBaseSearchPattern.from(" БАЗА 📚 "));
    }

    @Test
    void rejectsControlAndMalformedUnicode() {
        assertThrows(BadRequestException.class,
                () -> KnowledgeBaseSearchPattern.from("foo\nbar"));
        assertThrows(BadRequestException.class,
                () -> KnowledgeBaseSearchPattern.from("abc\ud800"));
        assertThrows(BadRequestException.class,
                () -> KnowledgeBaseSearchPattern.from("x".repeat(101)));
    }
}
