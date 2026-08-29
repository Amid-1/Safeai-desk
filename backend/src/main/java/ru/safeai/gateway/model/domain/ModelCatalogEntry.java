package ru.safeai.gateway.model.domain;

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
        Instant createdAt
) {
    public ModelCatalogEntry {
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
