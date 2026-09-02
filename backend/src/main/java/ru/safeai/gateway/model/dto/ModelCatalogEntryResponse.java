package ru.safeai.gateway.model.dto;

import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelCatalogSource;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Public model-catalog wire DTO.
 *
 * <p>All financial decimals are strings so JavaScript never receives them
 * through IEEE-754 JSON numbers.</p>
 */
public record ModelCatalogEntryResponse(
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
        String inputUsdPer1mTokens,
        String cachedInputUsdPer1mTokens,
        String cacheWriteInputUsdPer1mTokens,
        String outputUsdPer1mTokens,
        String extraPricingJson,
        String pricingVersion,
        Instant effectiveFrom,
        ModelCatalogSource source,
        UUID createdByUserId,
        Instant createdAt
) {
    public static ModelCatalogEntryResponse from(
            ModelCatalogEntry entry
    ) {
        return new ModelCatalogEntryResponse(
                entry.id(),
                entry.modelKey(),
                entry.version(),
                entry.provider(),
                entry.providerModelId(),
                entry.displayName(),
                entry.lifecycle(),
                entry.maxInputTokens(),
                entry.maxOutputTokens(),
                entry.capabilities(),
                entry.inputModalities(),
                entry.outputModalities(),
                entry.retentionStatus(),
                entry.retentionDays(),
                entry.trainingUseStatus(),
                entry.pricingStatus(),
                entry.pricingComplete(),
                decimal(entry.inputUsdPer1mTokens()),
                decimal(entry.cachedInputUsdPer1mTokens()),
                decimal(entry.cacheWriteInputUsdPer1mTokens()),
                decimal(entry.outputUsdPer1mTokens()),
                entry.extraPricingJson(),
                entry.pricingVersion(),
                entry.effectiveFrom(),
                entry.source(),
                entry.createdByUserId(),
                entry.createdAt()
        );
    }

    private static String decimal(
            BigDecimal value
    ) {
        return value == null
                ? null
                : value.toPlainString();
    }
}
