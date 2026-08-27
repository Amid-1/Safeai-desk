package ru.safeai.gateway.organization.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizationNameNormalizerTest {

    @Test
    void canonicalizesAsciiAndUnicodeWhitespace() {
        assertThat(
                OrganizationNameNormalizer.canonicalize(
                        " \t Demo\u00A0\u00A0Company \n "
                )
        ).isEqualTo("Demo Company");
    }


    @Test
    void confirmationIgnoresCaseQuotesAndWhitespace() {
        String expected =
                OrganizationNameNormalizer
                        .normalizeForConfirmation(
                                "ООО \"Зил\""
                        );

        List<String> acceptedVariants =
                List.of(
                        "ООО \"Зил\"",
                        "ооо \"зил\"",
                        "ООО «ЗИЛ»",
                        "ООО 'Зил'",
                        "ООО Зил",
                        "  ООО   «Зил»  ",
                        "ООО „Зил“",
                        "ООО “Зил”"
                );

        assertThat(
                acceptedVariants.stream()
                        .map(
                                OrganizationNameNormalizer
                                        ::normalizeForConfirmation
                        )
        ).allMatch(expected::equals);
    }

    @Test
    void confirmationKeepsSemanticIdentityStrict() {
        String expected =
                OrganizationNameNormalizer
                        .normalizeForConfirmation(
                                "ООО \"Зил\""
                        );

        List<String> rejectedVariants =
                List.of(
                        "Зил",
                        "ООО Зел",
                        "ООО Зилл",
                        "ООО Зио",
                        "АО Зил",
                        "ООО З И Л",
                        "ООО-Зил"
                );

        assertThat(
                rejectedVariants.stream()
                        .map(
                                OrganizationNameNormalizer
                                        ::normalizeForConfirmation
                        )
        ).noneMatch(expected::equals);
    }

    @Test
    void confirmationUsesStableRootLocale() {
        Locale previous = Locale.getDefault();

        try {
            Locale.setDefault(
                    Locale.forLanguageTag("tr-TR")
            );

            assertThat(
                    OrganizationNameNormalizer
                            .normalizeForConfirmation(
                                    " INFORMATION "
                            )
            ).isEqualTo("information");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void rejectsNullName() {
        assertThatThrownBy(() ->
                OrganizationNameNormalizer.canonicalize(null)
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("не должно быть пустым");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() ->
                OrganizationNameNormalizer.canonicalize(
                        " \u00A0\t "
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("не должно быть пустым");
    }

    @Test
    void rejectsResidualIsoControlCharacter() {
        assertThatThrownBy(() ->
                OrganizationNameNormalizer.canonicalize(
                        "Demo" + Character.toString(0) + "Company"
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("управляющие символы");
    }

    @Test
    void rejectsConfirmationContainingOnlyQuotes() {
        assertThatThrownBy(() ->
                OrganizationNameNormalizer
                        .normalizeForConfirmation(
                                " « \" ' ” "
                        )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("подтверждения");
    }

    @Test
    void rejectsCanonicalNameLongerThan255Characters() {
        assertThatThrownBy(() ->
                OrganizationNameNormalizer.canonicalize(
                        "a".repeat(256)
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("255");
    }
}
