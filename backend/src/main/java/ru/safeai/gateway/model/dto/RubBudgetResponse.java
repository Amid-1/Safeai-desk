package ru.safeai.gateway.model.dto;

import ru.safeai.gateway.model.domain.RubBudgetSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record RubBudgetResponse(
        String maxRequestCostRub, String monthlyBudgetRub, String rubPerUsd,
        LocalDate quoteDate, LocalDate effectiveDate, Instant fetchedAt, String source
) {
    public static RubBudgetResponse from(RubBudgetSnapshot value) {
        return value == null ? null : new RubBudgetResponse(
                decimal(value.maxRequestCostRub()), decimal(value.monthlyBudgetRub()),
                decimal(value.rubPerUsd()), value.quoteDate(), value.effectiveDate(),
                value.fetchedAt(), RubBudgetSnapshot.SOURCE);
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
