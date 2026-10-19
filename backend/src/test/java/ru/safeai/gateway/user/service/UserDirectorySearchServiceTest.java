package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserDirectorySearchServiceTest {

    @Test
    void blankSearchDoesNotAddPredicate() {
        assertThat(UserDirectorySearchService.searchPattern(null)).isNull();
        assertThat(UserDirectorySearchService.searchPattern("")).isNull();
        assertThat(UserDirectorySearchService.searchPattern("   ")).isNull();
    }

    @Test
    void searchTreatsPercentUnderscoreAndEscapeCharacterLiterally() {
        assertThat(UserDirectorySearchService.searchPattern("  A%_!B  "))
                .isEqualTo("%a!%!_!!b%");
    }

    @Test
    void searchRejectsControlCharactersBeforeTrimming() {
        for (String unsafe : new String[]{
                "abc" + '\0',
                '\0' + "abc",
                "  abc" + '\0' + "  ",
                "abc\n",
                "\tabc",
                "abc\r",
                "abc\u007f",
                "abc\u0085"
        }) {
            assertThatThrownBy(() -> UserDirectorySearchService.searchPattern(unsafe))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void whitespaceOnlyWithControlCharacterIsRejected() {
        // isBlank() is true for tabs; it must not bypass control validation.
        assertThatThrownBy(() -> UserDirectorySearchService.searchPattern("  \t  "))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void searchRejectsUnboundedInputAndAcceptsBoundary() {
        assertThat(UserDirectorySearchService.searchPattern("x".repeat(120)))
                .isEqualTo("%" + "x".repeat(120) + "%");
        assertThatThrownBy(() -> UserDirectorySearchService.searchPattern("x".repeat(121)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void searchValidatesLengthAfterUnicodeCaseFolding() {
        // Each U+0130 becomes two UTF-16 code units under Locale.ROOT.
        assertThatThrownBy(() -> UserDirectorySearchService.searchPattern("İ".repeat(61)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void searchRejectsUnpairedUtf16Surrogates() {
        String high = String.valueOf((char) 0xD800);
        String low = String.valueOf((char) 0xDC00);

        assertThatThrownBy(() -> UserDirectorySearchService.searchPattern("a" + high))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> UserDirectorySearchService.searchPattern(low + "a"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> UserDirectorySearchService.searchPattern(high + "a"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void searchNormalizesCaseWithoutChangingUnicode() {
        assertThat(UserDirectorySearchService.searchPattern("  ИВАН  "))
                .isEqualTo("%иван%");
        // A valid supplementary Unicode character must be preserved.
        assertThat(UserDirectorySearchService.searchPattern("Тест " + Character.toString(0x1F600)))
                .isEqualTo("%тест " + Character.toString(0x1F600) + "%");
    }

    @Test
    void searchAllowsUnicodeWhitespaceWhenItIsTrulyBlank() {
        // Valid Unicode whitespace is not an ISO control character.
        assertThat(UserDirectorySearchService.searchPattern("\u2003\u2003"))
                .isNull();
    }
}
