package ru.safeai.gateway.model.service;

import ru.safeai.gateway.common.exception.BadRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Application-boundary mirror of PostgreSQL numeric(30,12) used by V45 money
 * columns. Database constraints remain the final integrity boundary; this
 * helper keeps invalid API/service input from degrading into database errors.
 */
final class ModelControlPlaneNumericValidation {

    private static final int POSTGRES_DECIMAL_SCALE = 12;
    private static final int POSTGRES_DECIMAL_INTEGER_DIGITS = 18;
    private static final BigDecimal ONE_MILLION =
            new BigDecimal("1000000");

    private ModelControlPlaneNumericValidation() {
    }

    static void requireNonNegativeNumeric30Scale12(
            BigDecimal value,
            String field
    ) {
        if (value == null) {
            return;
        }
        if (value.signum() < 0) {
            throw new BadRequestException(
                    field + " не может быть отрицательным"
            );
        }

        if (violatesNonNegativeNumeric30Scale12(value)) {
            throw new BadRequestException(
                    field + " должен помещаться в PostgreSQL numeric(30,12)"
            );
        }
    }

    static BigDecimal normalizeNonNegativeNumeric30Scale12(
            BigDecimal value,
            String field
    ) {
        requireNonNegativeNumeric30Scale12(
                value,
                field
        );

        return value == null
                ? null
                : value.setScale(
                        POSTGRES_DECIMAL_SCALE,
                        RoundingMode.UNNECESSARY
                );
    }

    static void requireWorstCaseCostFitsNumeric30Scale12(
            BigDecimal inputRate,
            int maxInputTokens,
            BigDecimal outputRate,
            int maxOutputTokens
    ) {
        if (inputRate == null || outputRate == null) {
            return;
        }

        BigDecimal inputCost = tokenCost(
                maxInputTokens,
                inputRate
        );
        BigDecimal outputCost = tokenCost(
                maxOutputTokens,
                outputRate
        );
        BigDecimal worstCaseCost = inputCost
                .add(outputCost)
                .setScale(
                        POSTGRES_DECIMAL_SCALE,
                        RoundingMode.HALF_UP
                );

        if (violatesNonNegativeNumeric30Scale12(worstCaseCost)) {
            throw new BadRequestException(
                    "Worst-case model request cost должен помещаться "
                            + "в PostgreSQL numeric(30,12)"
            );
        }
    }

    static boolean violatesNonNegativeNumeric30Scale12(
            BigDecimal value
    ) {
        if (value == null || value.signum() < 0) {
            return true;
        }

        BigDecimal normalized = value.stripTrailingZeros();
        int scale = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(
                normalized.precision() - normalized.scale(),
                0
        );

        return scale > POSTGRES_DECIMAL_SCALE
                || integerDigits > POSTGRES_DECIMAL_INTEGER_DIGITS;
    }

    private static BigDecimal tokenCost(
            long tokens,
            BigDecimal rate
    ) {
        return BigDecimal.valueOf(tokens)
                .multiply(rate)
                .divide(
                        ONE_MILLION,
                        POSTGRES_DECIMAL_SCALE,
                        RoundingMode.HALF_UP
                );
    }
}
