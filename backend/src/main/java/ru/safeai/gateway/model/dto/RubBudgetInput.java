package ru.safeai.gateway.model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Rate fields identify the displayed quote; they are not trusted as a rate source. */
public record RubBudgetInput(
        @DecimalMin("0") @Digits(integer = 18, fraction = 12) BigDecimal maxRequestCostRub,
        @DecimalMin("0") @Digits(integer = 18, fraction = 12) BigDecimal monthlyBudgetRub,
        @NotNull @DecimalMin(value = "0.00000001") @Digits(integer = 10, fraction = 8) BigDecimal rubPerUsd,
        @NotNull LocalDate quoteDate,
        @NotNull LocalDate effectiveDate
) {}
