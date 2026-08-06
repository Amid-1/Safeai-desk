package ru.safeai.gateway.organization.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;

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
        ).isEqualTo(
                "Demo Company"
        );
    }

    @Test
    void createsStableRootLocaleNormalizedName() {
        Locale previous =
                Locale.getDefault();

        try {
            Locale.setDefault(
                    Locale.forLanguageTag(
                            "tr-TR"
                    )
            );

            assertThat(
                    OrganizationNameNormalizer.normalize(
                            " INFORMATION "
                    )
            ).isEqualTo(
                    "information"
            );
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void rejectsNullName() {
        assertThatThrownBy(() ->
                OrganizationNameNormalizer
                        .canonicalize(null)
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "не должно быть пустым"
                );
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() ->
                OrganizationNameNormalizer
                        .canonicalize(
                                " \u00A0\t "
                        )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "не должно быть пустым"
                );
    }

    @Test
    void rejectsCanonicalNameLongerThan255Characters() {
        String value =
                "a".repeat(256);

        assertThatThrownBy(() ->
                OrganizationNameNormalizer
                        .canonicalize(value)
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining("255");
    }
}