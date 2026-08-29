package ru.safeai.gateway.model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import ru.safeai.gateway.model.domain.BudgetEnforcement;

import java.math.BigDecimal;
import java.util.Set;

public record CreateOrganizationModelPolicyVersionRequest(
        @PositiveOrZero int expectedPreviousVersion,
        boolean enabled,
        @Size(max = 200) Set<@NotBlank @Size(max = 160) String> allowModelKeys,
        @Size(max = 200) Set<@NotBlank @Size(max = 160) String> denyModelKeys,
        @Size(max = 160) String defaultModelKey,
        @Positive Integer maxInputTokens,
        @Positive Integer maxOutputTokens,
        @DecimalMin("0") @Digits(integer = 18, fraction = 12) BigDecimal maxRequestCostUsd,
        @DecimalMin("0") @Digits(integer = 18, fraction = 12) BigDecimal monthlyBudgetUsd,
        @NotNull BudgetEnforcement budgetEnforcement,
        boolean requireCompletePricing,
        boolean requireNoTraining,
        boolean requireZeroDataRetention
) {
}
