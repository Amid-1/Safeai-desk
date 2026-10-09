package ru.safeai.gateway.common.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnicodeValidationSupportTest {

    @Test
    void acceptsBmpAndValidSurrogatePairs() {
        assertThatCode(() ->
                UnicodeValidationSupport
                        .requireWellFormedUtf16(
                                "ASCII Привет 😀",
                                "value"
                        )
        ).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnpairedHighSurrogate() {
        String invalid =
                "prefix"
                        + '\uD83D';

        assertThatThrownBy(() ->
                UnicodeValidationSupport
                        .requireWellFormedUtf16(
                                invalid,
                                "value"
                        )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "value содержит некорректный Unicode"
                );
    }

    @Test
    void rejectsUnpairedLowSurrogate() {
        String invalid =
                "prefix"
                        + '\uDE00';

        assertThatThrownBy(() ->
                UnicodeValidationSupport
                        .requireWellFormedUtf16(
                                invalid,
                                "value"
                        )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "value содержит некорректный Unicode"
                );
    }

    @Test
    void rejectsHighSurrogateFollowedByNonLowSurrogate() {
        String invalid =
                "prefix"
                        + '\uD83D'
                        + 'X';

        assertThatThrownBy(() ->
                UnicodeValidationSupport
                        .requireWellFormedUtf16(
                                invalid,
                                "value"
                        )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "value содержит некорректный Unicode"
                );
    }
}
