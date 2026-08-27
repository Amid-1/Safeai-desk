package ru.safeai.gateway.user.validation;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserEmailNormalizerTest {

    @Test
    void normalizesTrimAndCaseWithRootLocale() {
        Locale previous =
                Locale.getDefault();

        try {
            Locale.setDefault(
                    Locale.forLanguageTag(
                            "tr-TR"
                    )
            );

            assertThat(
                    UserEmailNormalizer
                            .normalizeAndValidate(
                                    " Admin@Test.COM "
                            )
            ).isEqualTo(
                    "admin@test.com"
            );
        } finally {
            Locale.setDefault(
                    previous
            );
        }
    }

    @Test
    void rejectsMissingAtSignAtApplicationBoundary() {
        assertThatThrownBy(() ->
                UserEmailNormalizer
                        .normalizeAndValidate(
                                "definitely-not-an-email"
                        )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "Некорректный формат email"
                );
    }

    @Test
    void rejectsMultipleAtSigns() {
        assertThatThrownBy(() ->
                UserEmailNormalizer
                        .normalizeAndValidate(
                                "user@@example.com"
                        )
        ).isInstanceOf(
                BadRequestException.class
        );
    }

    @Test
    void rejectsEmptyLocalOrDomainPart() {
        assertThatThrownBy(() ->
                UserEmailNormalizer
                        .normalizeAndValidate(
                                "@example.com"
                        )
        ).isInstanceOf(
                BadRequestException.class
        );

        assertThatThrownBy(() ->
                UserEmailNormalizer
                        .normalizeAndValidate(
                                "user@"
                        )
        ).isInstanceOf(
                BadRequestException.class
        );
    }

    @Test
    void rejectsWhitespaceInsideEmail() {
        assertThatThrownBy(() ->
                UserEmailNormalizer
                        .normalizeAndValidate(
                                "user name@example.com"
                        )
        ).isInstanceOf(
                BadRequestException.class
        );

        assertThatThrownBy(() ->
                UserEmailNormalizer
                        .normalizeAndValidate(
                                "user@example .com"
                        )
        ).isInstanceOf(
                BadRequestException.class
        );
    }

    @Test
    void rejectsControlCharacterInsideEmail() {
        assertThatThrownBy(() ->
                UserEmailNormalizer
                        .normalizeAndValidate(
                                "user\u0000name@example.com"
                        )
        ).isInstanceOf(
                BadRequestException.class
        );
    }

    @Test
    void rejectsTrailingControlCharacterBeforeTrimCanRemoveIt() {
        assertThatThrownBy(() ->
                UserEmailNormalizer
                        .normalizeAndValidate(
                                "user@example.com"
                                        + Character.toString(0)
                        )
        ).isInstanceOf(
                BadRequestException.class
        );
    }

    @Test
    void rejectsLeadingControlCharacterBeforeTrimCanRemoveIt() {
        assertThatThrownBy(() ->
                UserEmailNormalizer
                        .normalizeAndValidate(
                                Character.toString(0)
                                        + "user@example.com"
                        )
        ).isInstanceOf(
                BadRequestException.class
        );
    }

    @Test
    void rejectsMoreThan255Characters() {
        String email =
                "a".repeat(
                        244
                )
                        + "@example.com";

        assertThat(
                email.length()
        ).isGreaterThan(
                255
        );

        assertThatThrownBy(() ->
                UserEmailNormalizer
                        .normalizeAndValidate(
                                email
                        )
        ).isInstanceOf(
                BadRequestException.class
        );
    }

    @Test
    void storedInvalidEmailIsInternalInvariantFailure() {
        assertThatThrownBy(() ->
                UserEmailNormalizer
                        .normalizeStored(
                                "not-an-email"
                        )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "canonical email"
                );
    }

    @Test
    void storedControlCharacterIsInternalInvariantFailure() {
        assertThatThrownBy(() ->
                UserEmailNormalizer
                        .normalizeStored(
                                "user@example.com\u0000"
                        )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "canonical email"
                );
    }
}