package ru.safeai.gateway.model.domain;

import ru.safeai.gateway.ai.pricing.ModelPricingScheme;
import java.util.Objects;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public record ModelCatalogEntry(
        UUID id,
        String modelKey,
        int version,
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
        ModelCatalogSource source,
        UUID createdByUserId,
        Instant createdAt,
        ModelPricingScheme pricingScheme
) {
    public ModelCatalogEntry {
        Objects.requireNonNull(pricingScheme, "pricingScheme");
        capabilities = immutableEnumSet(
                capabilities,
                ModelCapability.class
        );
        inputModalities = immutableEnumSet(
                inputModalities,
                ModelModality.class
        );
        outputModalities = immutableEnumSet(
                outputModalities,
                ModelModality.class
        );
        extraPricingJson = extraPricingJson == null
                ? "{}"
                : extraPricingJson;
    }

    /** Legacy callers never silently obtain verified CONFIGURED pricing. */
    public ModelCatalogEntry(
        UUID id,
        String modelKey,
        int version,
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
        ModelCatalogSource source,
        UUID createdByUserId,
        Instant createdAt
    ) {
        this(
                id,
                modelKey,
                version,
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
                source,
                createdByUserId,
                createdAt,
                pricingStatus == ModelPricingStatus.FREE
                        ? ModelPricingScheme.FLAT_TOKEN_V1
                        : pricingStatus == ModelPricingStatus.CONFIGURED
                        ? ModelPricingScheme.LEGACY_FLAT_UNVERIFIED_V1
                        : ModelPricingScheme.UNSUPPORTED_COMPLEX_V1
        );
    }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(
            Set<E> values,
            Class<E> enumType
    ) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }

        EnumSet<E> copy = EnumSet.noneOf(enumType);
        copy.addAll(values);
        return Collections.unmodifiableSet(copy);
    }
}
