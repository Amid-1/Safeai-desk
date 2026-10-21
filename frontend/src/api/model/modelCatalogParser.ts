// frontend/src/api/model/modelCatalogParser.ts
// Назначение: строгая проверка версий каталога моделей.

import type { ModelCatalogEntry } from './modelTypes'
import { MODEL_CAPABILITIES, MODEL_MODALITIES, MODEL_LIFECYCLES, MODEL_RETENTION_STATUSES, MODEL_TRAINING_USE_STATUSES, MODEL_PRICING_STATUSES, MODEL_CATALOG_SOURCES } from './modelEnums'
import { requireObject, requireNonBlankString, optionalNonBlankString, requireBoolean, requireEnum, requireEnumArray, requirePositiveInteger, optionalNonNegativeInteger, optionalDecimalString, requireModelKey, requireUuid, requireInstant, requireJsonObjectString } from './modelWire'
import { validateCatalogSemantics } from './modelSemantics'

export function parseModelCatalogEntry(
    value: unknown,
    path = 'modelCatalog',
): ModelCatalogEntry {
    const raw = requireObject(value, path)

    const entry: ModelCatalogEntry = {
        id: requireUuid(raw.id, `${path}.id`),
        modelKey: requireModelKey(
            raw.modelKey,
            `${path}.modelKey`,
        ),
        version: requirePositiveInteger(
            raw.version,
            `${path}.version`,
        ),
        provider: requireNonBlankString(
            raw.provider,
            `${path}.provider`,
        ),
        providerModelId: requireNonBlankString(
            raw.providerModelId,
            `${path}.providerModelId`,
        ),
        displayName: requireNonBlankString(
            raw.displayName,
            `${path}.displayName`,
        ),
        lifecycle: requireEnum(
            raw.lifecycle,
            MODEL_LIFECYCLES,
            `${path}.lifecycle`,
        ),
        maxInputTokens: requirePositiveInteger(
            raw.maxInputTokens,
            `${path}.maxInputTokens`,
        ),
        maxOutputTokens: requirePositiveInteger(
            raw.maxOutputTokens,
            `${path}.maxOutputTokens`,
        ),
        capabilities: requireEnumArray(
            raw.capabilities,
            MODEL_CAPABILITIES,
            `${path}.capabilities`,
        ),
        inputModalities: requireEnumArray(
            raw.inputModalities,
            MODEL_MODALITIES,
            `${path}.inputModalities`,
        ),
        outputModalities: requireEnumArray(
            raw.outputModalities,
            MODEL_MODALITIES,
            `${path}.outputModalities`,
        ),
        retentionStatus: requireEnum(
            raw.retentionStatus,
            MODEL_RETENTION_STATUSES,
            `${path}.retentionStatus`,
        ),
        retentionDays: optionalNonNegativeInteger(
            raw.retentionDays,
            `${path}.retentionDays`,
        ),
        trainingUseStatus: requireEnum(
            raw.trainingUseStatus,
            MODEL_TRAINING_USE_STATUSES,
            `${path}.trainingUseStatus`,
        ),
        pricingStatus: requireEnum(
            raw.pricingStatus,
            MODEL_PRICING_STATUSES,
            `${path}.pricingStatus`,
        ),
        pricingComplete: requireBoolean(
            raw.pricingComplete,
            `${path}.pricingComplete`,
        ),
        inputUsdPer1mTokens: optionalDecimalString(
            raw.inputUsdPer1mTokens,
            `${path}.inputUsdPer1mTokens`,
        ),
        cachedInputUsdPer1mTokens: optionalDecimalString(
            raw.cachedInputUsdPer1mTokens,
            `${path}.cachedInputUsdPer1mTokens`,
        ),
        cacheWriteInputUsdPer1mTokens: optionalDecimalString(
            raw.cacheWriteInputUsdPer1mTokens,
            `${path}.cacheWriteInputUsdPer1mTokens`,
        ),
        outputUsdPer1mTokens: optionalDecimalString(
            raw.outputUsdPer1mTokens,
            `${path}.outputUsdPer1mTokens`,
        ),
        extraPricingJson: requireJsonObjectString(
            raw.extraPricingJson,
            `${path}.extraPricingJson`,
        ),
        pricingVersion: optionalNonBlankString(
            raw.pricingVersion,
            `${path}.pricingVersion`,
        ),
        effectiveFrom: requireInstant(
            raw.effectiveFrom,
            `${path}.effectiveFrom`,
        ),
        source: requireEnum(
            raw.source,
            MODEL_CATALOG_SOURCES,
            `${path}.source`,
        ),
        createdByUserId: requireUuid(
            raw.createdByUserId,
            `${path}.createdByUserId`,
        ),
        createdAt: requireInstant(
            raw.createdAt,
            `${path}.createdAt`,
        ),
    }

    validateCatalogSemantics(entry, path)
    return entry
}
