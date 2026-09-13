package ru.safeai.gateway.model.service;

import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;

import java.util.Objects;

/** Builds bounded administrative audit evidence for catalog version creation. */
final class ModelCatalogAuditDetailsFactory {

    private ModelCatalogAuditDetailsFactory() {
    }

    static AuditDetails create(
            ModelCatalogEntry entry
    ) {
        Objects.requireNonNull(
                entry,
                "entry не должен быть null"
        );

        return new ModelCatalogAuditDetails(
                entry.id(),
                entry.modelKey(),
                entry.version(),
                entry.provider(),
                entry.providerModelId(),
                entry.lifecycle(),
                entry.pricingStatus(),
                entry.pricingComplete(),
                entry.source(),
                entry.maxInputTokens(),
                entry.maxOutputTokens(),
                entry.capabilities(),
                entry.inputModalities(),
                entry.outputModalities(),
                entry.retentionStatus(),
                entry.trainingUseStatus(),
                entry.effectiveFrom().toString(),
                entry.pricingVersion()
        );
    }
}
