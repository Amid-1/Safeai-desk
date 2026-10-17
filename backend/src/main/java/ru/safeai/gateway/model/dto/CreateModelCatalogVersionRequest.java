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

import ru.safeai.gateway.ai.pricing.ModelPricingScheme;
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
        @PositiveOrZero int expectedPreviousVersion,
        ModelPricingScheme pricingScheme
) {
    /** Compatibility constructor; CONFIGURED still needs explicit scheme. */
    public CreateModelCatalogVersionRequest(
        String modelKey,
        String provider,
        String providerModelId,
        String displayName,
        ModelLifecycle lifecycle,
        int maxInputTokens,
        int maxOutputTokens,
        Set<ModelCapability> capabilities,
        Set<ModelModality> inputModalities,
        Set<ModelModality> outputModalities,
        ModelRetentionStatus retentionStatus,
        Integer retentionDays,
        ModelTrainingUseStatus trainingUseStatus,
        ModelPricingStatus pricingStatus,
        boolean pricingComplete,
        BigDecimal inputUsdPer1mTokens,
        BigDecimal cachedInputUsdPer1mTokens,
        BigDecimal cacheWriteInputUsdPer1mTokens,
        BigDecimal outputUsdPer1mTokens,
        String extraPricingJson,
        String pricingVersion,
        Instant effectiveFrom,
        int expectedPreviousVersion
    ) {
        this(
                modelKey,
                provider,
                providerModelId,
                displayName,
                lifecycle,
                maxInputTokens,
                maxOutputTokens,
                capabilities,
                inputModalities,
                outputModalities,
                retentionStatus,
                retentionDays,
                trainingUseStatus,
                pricingStatus,
                pricingComplete,
                inputUsdPer1mTokens,
                cachedInputUsdPer1mTokens,
                cacheWriteInputUsdPer1mTokens,
                outputUsdPer1mTokens,
                extraPricingJson,
                pricingVersion,
                effectiveFrom,
                expectedPreviousVersion,
                null
        );
    }
}
