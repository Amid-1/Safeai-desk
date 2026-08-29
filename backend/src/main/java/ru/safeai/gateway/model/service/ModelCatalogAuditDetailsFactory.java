package ru.safeai.gateway.model.service;

import ru.safeai.gateway.model.domain.ModelCatalogEntry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builds bounded administrative audit evidence for catalog version creation. */
final class ModelCatalogAuditDetailsFactory {

    private ModelCatalogAuditDetailsFactory() {
    }

    static Map<String, Object> create(
            ModelCatalogEntry entry
    ) {
        Objects.requireNonNull(
                entry,
                "entry не должен быть null"
        );

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("catalogEntryId", entry.id());
        details.put("modelKey", entry.modelKey());
        details.put("version", entry.version());
        details.put("provider", entry.provider());
        details.put("providerModelId", entry.providerModelId());
        details.put("lifecycle", entry.lifecycle());
        details.put("pricingStatus", entry.pricingStatus());
        details.put("pricingComplete", entry.pricingComplete());
        details.put("source", entry.source());
        details.put("maxInputTokens", entry.maxInputTokens());
        details.put("maxOutputTokens", entry.maxOutputTokens());
        details.put("capabilities", entry.capabilities());
        details.put("inputModalities", entry.inputModalities());
        details.put("outputModalities", entry.outputModalities());
        details.put("retentionStatus", entry.retentionStatus());
        details.put("trainingUseStatus", entry.trainingUseStatus());
        details.put("effectiveFrom", entry.effectiveFrom().toString());

        if (entry.pricingVersion() != null) {
            details.put("pricingVersion", entry.pricingVersion());
        }

        return Map.copyOf(details);
    }
}
