package ru.safeai.gateway.model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record CreateOrganizationModelPolicyVersionRequest(
        @PositiveOrZero int expectedPreviousVersion,
        boolean enabled,
        @Size(max = 200) Set<@NotBlank @Size(max = 160) String> allowModelKeys,
        @Size(max = 200) Set<@NotBlank @Size(max = 160) String> denyModelKeys,
        @Size(max = 160) String defaultModelKey,
        @Positive @JsonDeserialize(using = StrictPolicyTokenLimitDeserializer.class) Integer maxInputTokens,
        @Positive @JsonDeserialize(using = StrictPolicyTokenLimitDeserializer.class) Integer maxOutputTokens,
        @DecimalMin("0") @Digits(integer = 18, fraction = 12) BigDecimal maxRequestCostUsd,
        @DecimalMin("0") @Digits(integer = 18, fraction = 12) BigDecimal monthlyBudgetUsd,
        @NotNull BudgetEnforcement budgetEnforcement,
        boolean requireCompletePricing,
        boolean requireNoTraining,
        boolean requireZeroDataRetention,
        @Valid RubBudgetInput rubBudget,
        UUID previewConfirmationId,
        @JsonDeserialize(using = StrictPolicyLockoutAcknowledgmentDeserializer.class) Boolean acknowledgeLockout
) {
    public CreateOrganizationModelPolicyVersionRequest {
        // Отсутствие или null никогда не означает согласие на блокировку.
        acknowledgeLockout = Boolean.TRUE.equals(acknowledgeLockout);
    }

    public CreateOrganizationModelPolicyVersionRequest(int expectedPreviousVersion, boolean enabled,
            Set<String> allowModelKeys, Set<String> denyModelKeys, String defaultModelKey,
            Integer maxInputTokens, Integer maxOutputTokens, BigDecimal maxRequestCostUsd,
            BigDecimal monthlyBudgetUsd, BudgetEnforcement budgetEnforcement,
            boolean requireCompletePricing, boolean requireNoTraining, boolean requireZeroDataRetention,
            RubBudgetInput rubBudget) {
        this(expectedPreviousVersion, enabled, allowModelKeys, denyModelKeys, defaultModelKey,
                maxInputTokens, maxOutputTokens, maxRequestCostUsd, monthlyBudgetUsd, budgetEnforcement,
                requireCompletePricing, requireNoTraining, requireZeroDataRetention, rubBudget, null, false);
    }

    @Override
    public @NonNull String toString() {
        return "CreateOrganizationModelPolicyVersionRequest[expectedPreviousVersion="
                + expectedPreviousVersion + ", enabled=" + enabled + ", confirmation=<redacted>]";
    }
    public CreateOrganizationModelPolicyVersionRequest(int expectedPreviousVersion, boolean enabled,
            Set<String> allowModelKeys, Set<String> denyModelKeys, String defaultModelKey,
            Integer maxInputTokens, Integer maxOutputTokens, BigDecimal maxRequestCostUsd,
            BigDecimal monthlyBudgetUsd, BudgetEnforcement budgetEnforcement,
            boolean requireCompletePricing, boolean requireNoTraining, boolean requireZeroDataRetention) {
        this(expectedPreviousVersion, enabled, allowModelKeys, denyModelKeys, defaultModelKey,
                maxInputTokens, maxOutputTokens, maxRequestCostUsd, monthlyBudgetUsd, budgetEnforcement,
                requireCompletePricing, requireNoTraining, requireZeroDataRetention, null);
    }
}
