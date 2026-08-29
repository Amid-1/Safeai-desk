package ru.safeai.gateway.model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public record CreateModelCatalogVersionRequest(
        @NotBlank @Size(max = 160) String modelKey,
        @NotBlank @Size(max = 32) String provider,
        @NotBlank @Size(max = 100) String providerModelId,
        @NotBlank @Size(max = 255) String displayName,
        @NotNull ModelLifecycle lifecycle,
        @Positive int maxInputTokens,
        @Positive int maxOutputTokens,
        Set<@NotNull ModelCapability> capabilities,
        Set<@NotNull ModelModality> inputModalities,
        Set<@NotNull ModelModality> outputModalities,
        @NotNull ModelRetentionStatus retentionStatus,
        @PositiveOrZero Integer retentionDays,
        @NotNull ModelTrainingUseStatus trainingUseStatus,
        @NotNull ModelPricingStatus pricingStatus,
        boolean pricingComplete,
        @DecimalMin("0") @Digits(integer = 18, fraction = 12) BigDecimal inputUsdPer1mTokens,
        @DecimalMin("0") @Digits(integer = 18, fraction = 12) BigDecimal cachedInputUsdPer1mTokens,
        @DecimalMin("0") @Digits(integer = 18, fraction = 12) BigDecimal cacheWriteInputUsdPer1mTokens,
        @DecimalMin("0") @Digits(integer = 18, fraction = 12) BigDecimal outputUsdPer1mTokens,
        @Size(max = 16_000) String extraPricingJson,
        @Size(max = 64) String pricingVersion,
        Instant effectiveFrom,
        @PositiveOrZero int expectedPreviousVersion
) {
}
