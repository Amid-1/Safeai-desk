package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatContentNormalizerTest {

    private final ChatContentNormalizer normalizer =
            new ChatContentNormalizer(properties(100));

    @Test
    void normalizesCrLfAndCrButPreservesMeaningfulSpaces() {
        assertThat(normalizer.normalize("  a\r\nb\r  "))
                .isEqualTo("  a\nb\n  ");
    }

    @Test
    void tabsAndNewLinesAreAllowed() {
        assertThat(normalizer.normalize("a\tb\nc"))
                .isEqualTo("a\tb\nc");
    }

    @Test
    void blankContentIsRejected() {
        assertThatThrownBy(() -> normalizer.normalize(" \n\t "))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void isoControlCharacterIsRejected() {
        assertThatThrownBy(() -> normalizer.normalize("abc\u0000def"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("управляющий");
    }

    @Test
    void configuredLengthLimitIsEnforced() {
        ChatContentNormalizer shortNormalizer =
                new ChatContentNormalizer(properties(5));

        assertThatThrownBy(() -> shortNormalizer.normalize("123456"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("5");
    }

    @Test
    void hashIsStableForSameNormalizedContent() {
        String content = normalizer.normalize("Привет\r\nмир");

        assertThat(normalizer.sha256(content))
                .isEqualTo(normalizer.sha256("Привет\nмир"))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void differentContentHasDifferentIdempotencyHash() {
        assertThat(normalizer.sha256("one"))
                .isNotEqualTo(normalizer.sha256("two"));
    }

    private static ChatProperties properties(int maxMessageChars) {
        return new ChatProperties(
                50,
                50,
                100,
                100,
                maxMessageChars,
                Duration.ofMinutes(3),
                4,
                1000
        );
    }
}
