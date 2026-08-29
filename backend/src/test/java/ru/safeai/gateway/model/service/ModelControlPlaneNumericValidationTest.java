package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.math.BigDecimal;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelControlPlaneNumericValidationTest {

    @Test
    void nullIsAcceptedForOptionalMoneyFields() {
        assertThatCode(() ->
                ModelControlPlaneNumericValidation
                        .requireNonNegativeNumeric30Scale12(
                                null,
                                "price"
                        )
        ).doesNotThrowAnyException();
    }

    @Test
    void negativeValueIsRejectedBeforeDatabaseWrite() {
        assertThatThrownBy(() ->
                ModelControlPlaneNumericValidation
                        .requireNonNegativeNumeric30Scale12(
                                new BigDecimal("-0.01"),
                                "price"
                        )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("price")
                .hasMessageContaining("отрицательным");
    }

    @Test
    void scaleGreaterThanTwelveIsRejected() {
        assertThatThrownBy(() ->
                ModelControlPlaneNumericValidation
                        .requireNonNegativeNumeric30Scale12(
                                new BigDecimal("0.0000000000001"),
                                "price"
                        )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("numeric(30,12)");
    }

    @Test
    void moreThanEighteenIntegerDigitsIsRejected() {
        assertThatThrownBy(() ->
                ModelControlPlaneNumericValidation
                        .requireNonNegativeNumeric30Scale12(
                                new BigDecimal("1000000000000000000"),
                                "price"
                        )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("numeric(30,12)");
    }

    @Test
    void trailingZerosDoNotCreateFalseScaleOverflow() {
        BigDecimal value = new BigDecimal(
                "1.230000000000000000000000"
        );

        assertThat(
                ModelControlPlaneNumericValidation
                        .violatesNonNegativeNumeric30Scale12(value)
        ).isFalse();
    }

    @Test
    void validMoneyIsCanonicalizedToDatabaseScaleTwelve() {
        BigDecimal normalized = Objects.requireNonNull(
                ModelControlPlaneNumericValidation
                        .normalizeNonNegativeNumeric30Scale12(
                                new BigDecimal("1.23"),
                                "price"
                        ),
                "Non-null valid money must normalize to a non-null value"
        );

        assertThat(normalized)
                .isEqualTo(
                        new BigDecimal("1.230000000000")
                );
        assertThat(normalized.scale())
                .isEqualTo(12);
    }

    @Test
    void nullMoneyRemainsNullDuringCanonicalization() {
        assertThat(
                ModelControlPlaneNumericValidation
                        .normalizeNonNegativeNumeric30Scale12(
                                null,
                                "price"
                        )
        ).isNull();
    }

    @Test
    void worstCaseCostOverflowIsRejectedAtCatalogBoundary() {
        assertThatThrownBy(() ->
                ModelControlPlaneNumericValidation
                        .requireWorstCaseCostFitsNumeric30Scale12(
                                new BigDecimal("999999999999999999"),
                                Integer.MAX_VALUE,
                                new BigDecimal("999999999999999999"),
                                Integer.MAX_VALUE
                        )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Worst-case")
                .hasMessageContaining("numeric(30,12)");
    }

    @Test
    void validBoundaryValueIsAccepted() {
        BigDecimal maximum = new BigDecimal(
                "999999999999999999.999999999999"
        );

        assertThatCode(() ->
                ModelControlPlaneNumericValidation
                        .requireNonNegativeNumeric30Scale12(
                                maximum,
                                "price"
                        )
        ).doesNotThrowAnyException();
    }
}
