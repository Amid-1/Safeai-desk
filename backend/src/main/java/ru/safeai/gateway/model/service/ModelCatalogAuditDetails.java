package ru.safeai.gateway.model.service;

import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelCatalogSource;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Typed, immutable audit evidence for a model catalog version. */
record ModelCatalogAuditDetails(
        UUID catalogEntryId,
        String modelKey,
        int version,
        String provider,
        String providerModelId,
        ModelLifecycle lifecycle,
        ModelPricingStatus pricingStatus,
        boolean pricingComplete,
        ModelCatalogSource source,
        int maxInputTokens,
        int maxOutputTokens,
        Set<ModelCapability> capabilities,
        Set<ModelModality> inputModalities,
        Set<ModelModality> outputModalities,
        ModelRetentionStatus retentionStatus,
        ModelTrainingUseStatus trainingUseStatus,
        String effectiveFrom,
        String pricingVersion
) implements AuditDetails {

    ModelCatalogAuditDetails {
        Objects.requireNonNull(catalogEntryId, "catalogEntryId не должен быть null");
        Objects.requireNonNull(modelKey, "modelKey не должен быть null");
        Objects.requireNonNull(provider, "provider не должен быть null");
        Objects.requireNonNull(providerModelId, "providerModelId не должен быть null");
        Objects.requireNonNull(lifecycle, "lifecycle не должен быть null");
        Objects.requireNonNull(pricingStatus, "pricingStatus не должен быть null");
        Objects.requireNonNull(source, "source не должен быть null");
        capabilities = Set.copyOf(Objects.requireNonNull(
                capabilities,
                "capabilities не должен быть null"
        ));
        inputModalities = Set.copyOf(Objects.requireNonNull(
                inputModalities,
                "inputModalities не должен быть null"
        ));
        outputModalities = Set.copyOf(Objects.requireNonNull(
                outputModalities,
                "outputModalities не должен быть null"
        ));
        Objects.requireNonNull(retentionStatus, "retentionStatus не должен быть null");
        Objects.requireNonNull(trainingUseStatus, "trainingUseStatus не должен быть null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom не должен быть null");
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("catalogEntryId", catalogEntryId);
        details.put("modelKey", modelKey);
        details.put("version", version);
        details.put("provider", provider);
        details.put("providerModelId", providerModelId);
        details.put("lifecycle", lifecycle);
        details.put("pricingStatus", pricingStatus);
        details.put("pricingComplete", pricingComplete);
        details.put("source", source);
        details.put("maxInputTokens", maxInputTokens);
        details.put("maxOutputTokens", maxOutputTokens);
        details.put("capabilities", capabilities);
        details.put("inputModalities", inputModalities);
        details.put("outputModalities", outputModalities);
        details.put("retentionStatus", retentionStatus);
        details.put("trainingUseStatus", trainingUseStatus);
        details.put("effectiveFrom", effectiveFrom);

        if (pricingVersion != null) {
            details.put("pricingVersion", pricingVersion);
        }

        return Map.copyOf(details);
    }
}
