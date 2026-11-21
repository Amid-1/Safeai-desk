package ru.safeai.gateway.model.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/** Immutable conversion evidence. All enforcement still uses the USD columns. */
public record RubBudgetSnapshot(
        BigDecimal maxRequestCostRub, BigDecimal monthlyBudgetRub,
        BigDecimal rubPerUsd, LocalDate quoteDate, LocalDate effectiveDate, Instant fetchedAt
) {
    public static final String SOURCE = "CBR";

    public BigDecimal maxRequestCostUsd() {
        return toUsd(maxRequestCostRub, rubPerUsd);
    }

    public BigDecimal monthlyBudgetUsd() {
        return toUsd(monthlyBudgetRub, rubPerUsd);
    }

    public static BigDecimal toUsd(BigDecimal rub, BigDecimal rate) {
        // A budget ceiling must never increase due to fractional rounding.
        return rub == null ? null : rub.divide(rate, 12, RoundingMode.DOWN);
    }
}
