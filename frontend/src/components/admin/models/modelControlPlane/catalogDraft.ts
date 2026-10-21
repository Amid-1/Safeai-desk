// frontend/src/components/admin/models/modelControlPlane/catalogDraft.ts
import type {
    ModelCapability, ModelCatalogEntry, RuntimeModelStatus,
    CreateModelCatalogVersionRequest,
} from '../../../../api/modelApi'
import type { CatalogDraft } from './modelTypes'
import { normalizeModelKey } from './modelKeys'
import {
    requireText, parsePositiveInteger, parseOptionalNonNegativeInteger,
    parseOptionalDecimal, nullableText, normalizeJsonObject,
} from './modelValues'
import { validatePricingDraft } from './modelPricing'


export function createCatalogDraft(
    base: ModelCatalogEntry | null,
    runtime: RuntimeModelStatus,
): CatalogDraft {
    if (base) {
        return {
            modelKey: base.modelKey,
            provider: base.provider,
            providerModelId: base.providerModelId,
            displayName: base.displayName,
            lifecycle: base.lifecycle,
            maxInputTokens: String(base.maxInputTokens),
            maxOutputTokens: String(base.maxOutputTokens),
            capabilities: [...base.capabilities],
            inputModalities: [...base.inputModalities],
            outputModalities: [...base.outputModalities],
            retentionStatus: base.retentionStatus,
            retentionDays: base.retentionDays == null
                ? ''
                : String(base.retentionDays),
            trainingUseStatus: base.trainingUseStatus,
            pricingStatus: base.pricingStatus,
            pricingComplete: base.pricingComplete,
            inputUsdPer1mTokens: base.inputUsdPer1mTokens ?? '',
            cachedInputUsdPer1mTokens: base.cachedInputUsdPer1mTokens ?? '',
            cacheWriteInputUsdPer1mTokens: base.cacheWriteInputUsdPer1mTokens ?? '',
            outputUsdPer1mTokens: base.outputUsdPer1mTokens ?? '',
            extraPricingJson: base.extraPricingJson,
            pricingVersion: base.pricingVersion ?? '',
            effectiveFrom: '',
        }
    }

    return {
        modelKey: `${runtime.provider}:${runtime.model}`
            .toLowerCase(),
        provider: runtime.provider,
        providerModelId: runtime.model,
        displayName: runtime.model,
        lifecycle: 'ACTIVE',
        maxInputTokens: String(runtime.maxInputTokens),
        maxOutputTokens: String(runtime.maxOutputTokens),
        capabilities: runtimeCapabilities(runtime),
        inputModalities: ['TEXT'],
        outputModalities: ['TEXT'],
        retentionStatus: 'NOT_DECLARED',
        retentionDays: '',
        trainingUseStatus: 'NOT_DECLARED',
        pricingStatus: runtime.pricingStatus === 'FREE'
            ? 'FREE'
            : runtime.pricingStatus === 'CONFIGURED'
                ? 'INCOMPLETE'
                : 'UNPRICED',
        pricingComplete: runtime.pricingStatus === 'FREE',
        inputUsdPer1mTokens: runtime.inputUsdPer1mTokens ?? '',
        cachedInputUsdPer1mTokens: '',
        cacheWriteInputUsdPer1mTokens: '',
        outputUsdPer1mTokens: runtime.outputUsdPer1mTokens ?? '',
        extraPricingJson: '{}',
        pricingVersion: runtime.pricingVersion ?? '',
        effectiveFrom: '',
    }
}

export function normalizeDraftForPricingStatus(
    draft: CatalogDraft,
): CatalogDraft {
    switch (draft.pricingStatus) {
        case 'UNPRICED':
            return {
                ...draft,
                pricingComplete: false,
                inputUsdPer1mTokens: '',
                cachedInputUsdPer1mTokens: '',
                cacheWriteInputUsdPer1mTokens: '',
                outputUsdPer1mTokens: '',
                pricingVersion: '',
                extraPricingJson: '{}',
            }

        case 'FREE':
            return {
                ...draft,
                pricingComplete: true,
                inputUsdPer1mTokens: '0',
                cachedInputUsdPer1mTokens: '0',
                cacheWriteInputUsdPer1mTokens: '0',
                outputUsdPer1mTokens: '0',
                extraPricingJson: '{}',
            }

        case 'CONFIGURED':
            return {
                ...draft,
                pricingComplete: true,
            }

        case 'INCOMPLETE':
            return {
                ...draft,
                pricingComplete: false,
            }
    }
}

export function buildCatalogRequest(
    draft: CatalogDraft,
    expectedPreviousVersion: number,
): CreateModelCatalogVersionRequest {
    const modelKey =
        normalizeModelKey(draft.modelKey)

    const provider =
        requireText(
            draft.provider,
            'Провайдер',
        ).toLowerCase()

    const providerModelId =
        requireText(
            draft.providerModelId,
            'ID модели у провайдера',
        )

    const displayName =
        requireText(
            draft.displayName,
            'Название модели',
        )

    const maxInputTokens =
        parsePositiveInteger(
            draft.maxInputTokens,
            'Максимум входных токенов',
        )

    const maxOutputTokens =
        parsePositiveInteger(
            draft.maxOutputTokens,
            'Максимум выходных токенов',
        )

    if (draft.inputModalities.length === 0) {
        throw new Error(
            'Выберите минимум один тип входных данных.',
        )
    }

    if (draft.outputModalities.length === 0) {
        throw new Error(
            'Выберите минимум один тип выходных данных.',
        )
    }

    if (draft.outputModalities.includes('IMAGE')) {
        throw new Error(
            'Изображения пока не поддерживаются как выходной формат каталога.',
        )
    }

    const retentionDays =
        parseOptionalNonNegativeInteger(
            draft.retentionDays,
            'Срок хранения',
        )

    if (
        draft.retentionStatus
            === 'ZERO_DATA_RETENTION'
        && retentionDays !== null
        && retentionDays !== 0
    ) {
        throw new Error(
            'Для режима «Данные не сохраняются» срок хранения должен быть 0 или пустым.',
        )
    }

    const input =
        parseOptionalDecimal(
            draft.inputUsdPer1mTokens,
            'Стоимость входных токенов',
        )

    const cachedInput =
        parseOptionalDecimal(
            draft.cachedInputUsdPer1mTokens,
            'Стоимость кэшированных входных токенов',
        )

    const cacheWrite =
        parseOptionalDecimal(
            draft.cacheWriteInputUsdPer1mTokens,
            'Стоимость записи кэша',
        )

    const output =
        parseOptionalDecimal(
            draft.outputUsdPer1mTokens,
            'Стоимость выходных токенов',
        )

    const pricingVersion =
        nullableText(
            draft.pricingVersion,
        )

    const extraPricingJson =
        normalizeJsonObject(
            draft.extraPricingJson,
        )

    validatePricingDraft(
        draft.pricingStatus,
        draft.pricingComplete,
        input,
        cachedInput,
        cacheWrite,
        output,
        pricingVersion,
        extraPricingJson,
    )

    return {
        modelKey,
        provider,
        providerModelId,
        displayName,
        lifecycle: draft.lifecycle,
        maxInputTokens,
        maxOutputTokens,
        capabilities: [...draft.capabilities],
        inputModalities: [...draft.inputModalities],
        outputModalities: [...draft.outputModalities],
        retentionStatus: draft.retentionStatus,
        retentionDays,
        trainingUseStatus: draft.trainingUseStatus,
        pricingStatus: draft.pricingStatus,
        pricingComplete: draft.pricingComplete,
        inputUsdPer1mTokens: input,
        cachedInputUsdPer1mTokens: cachedInput,
        cacheWriteInputUsdPer1mTokens: cacheWrite,
        outputUsdPer1mTokens: output,
        extraPricingJson,
        pricingVersion,
        effectiveFrom: draft.effectiveFrom
            ? new Date(
                draft.effectiveFrom,
            ).toISOString()
            : null,
        expectedPreviousVersion,
    }
}

export function runtimeCapabilities(
    runtime: RuntimeModelStatus,
): ModelCapability[] {
    const result: ModelCapability[] = []

    if (runtime.toolsSupported) {
        result.push('TOOLS')
    }

    if (runtime.visionSupported) {
        result.push('VISION')
    }

    if (runtime.structuredOutputSupported) {
        result.push('STRUCTURED_OUTPUT')
    }

    return result
}
