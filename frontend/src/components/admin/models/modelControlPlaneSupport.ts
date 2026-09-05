import type {
    BudgetEnforcement,
    CreateModelCatalogVersionRequest,
    CreateOrganizationModelPolicyVersionRequest,
    ModelCapability,
    ModelCatalogEntry,
    ModelLifecycle,
    ModelModality,
    ModelPricingStatus,
    ModelRetentionStatus,
    ModelTrainingUseStatus,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../api/modelApi'

const MODEL_KEY_PATTERN =
    /^[a-z0-9][a-z0-9._:/-]{0,159}$/

const DECIMAL_PATTERN =
    /^\d+(?:\.\d+)?$/

export type CatalogDraft = {
    modelKey: string
    provider: string
    providerModelId: string
    displayName: string
    lifecycle: ModelLifecycle
    maxInputTokens: string
    maxOutputTokens: string
    capabilities: ModelCapability[]
    inputModalities: ModelModality[]
    outputModalities: ModelModality[]
    retentionStatus: ModelRetentionStatus
    retentionDays: string
    trainingUseStatus: ModelTrainingUseStatus
    pricingStatus: ModelPricingStatus
    pricingComplete: boolean
    inputUsdPer1mTokens: string
    cachedInputUsdPer1mTokens: string
    cacheWriteInputUsdPer1mTokens: string
    outputUsdPer1mTokens: string
    extraPricingJson: string
    pricingVersion: string
    effectiveFrom: string
}


export type PolicyDraft = {
    enabled: boolean
    allowModelKeys: string
    denyModelKeys: string
    defaultModelKey: string
    maxInputTokens: string
    maxOutputTokens: string
    maxRequestCostUsd: string
    monthlyBudgetUsd: string
    budgetEnforcement: BudgetEnforcement
    requireCompletePricing: boolean
    requireNoTraining: boolean
    requireZeroDataRetention: boolean
}


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

export function createPolicyDraft(
    policy: OrganizationModelPolicy,
): PolicyDraft {
    return {
        enabled: policy.enabled,
        allowModelKeys:
            policy.allowModelKeys.join('\n'),
        denyModelKeys:
            policy.denyModelKeys.join('\n'),
        defaultModelKey:
            policy.defaultModelKey ?? '',
        maxInputTokens:
            policy.maxInputTokens == null
                ? ''
                : String(policy.maxInputTokens),
        maxOutputTokens:
            policy.maxOutputTokens == null
                ? ''
                : String(policy.maxOutputTokens),
        maxRequestCostUsd:
            policy.maxRequestCostUsd ?? '',
        monthlyBudgetUsd:
            policy.monthlyBudgetUsd ?? '',
        budgetEnforcement:
            policy.budgetEnforcement,
        requireCompletePricing:
            policy.requireCompletePricing,
        requireNoTraining:
            policy.requireNoTraining,
        requireZeroDataRetention:
            policy.requireZeroDataRetention,
    }
}

export function buildPolicyRequest(
    draft: PolicyDraft,
    expectedPreviousVersion: number,
): CreateOrganizationModelPolicyVersionRequest {
    const allowModelKeys =
        parseModelKeyList(
            draft.allowModelKeys,
        )

    const denyModelKeys =
        parseModelKeyList(
            draft.denyModelKeys,
        )

    const overlap =
        allowModelKeys.filter(
            (key) =>
                denyModelKeys.includes(key),
        )

    if (overlap.length > 0) {
        throw new Error(
            `Одна и та же модель указана и в разрешённых, и в запрещённых: ${overlap.join(', ')}`,
        )
    }

    const defaultModelKey =
        draft.defaultModelKey.trim()
            ? normalizeModelKey(
                draft.defaultModelKey,
            )
            : null

    if (
        defaultModelKey
        && denyModelKeys.includes(
            defaultModelKey,
        )
    ) {
        throw new Error(
            'Модель по умолчанию не может находиться в списке запрещённых.',
        )
    }

    if (
        defaultModelKey
        && allowModelKeys.length > 0
        && !allowModelKeys.includes(
            defaultModelKey,
        )
    ) {
        throw new Error(
            'Если список разрешённых моделей заполнен, модель по умолчанию должна входить в него.',
        )
    }

    return {
        expectedPreviousVersion,
        enabled: draft.enabled,
        allowModelKeys,
        denyModelKeys,
        defaultModelKey,
        maxInputTokens:
            parseOptionalPositiveInteger(
                draft.maxInputTokens,
                'Максимум входных токенов',
            ),
        maxOutputTokens:
            parseOptionalPositiveInteger(
                draft.maxOutputTokens,
                'Максимум выходных токенов',
            ),
        maxRequestCostUsd:
            parseOptionalDecimal(
                draft.maxRequestCostUsd,
                'Максимальная стоимость запроса',
            ),
        monthlyBudgetUsd:
            parseOptionalDecimal(
                draft.monthlyBudgetUsd,
                'Месячный бюджет',
            ),
        budgetEnforcement:
            draft.budgetEnforcement,
        requireCompletePricing:
            draft.requireCompletePricing,
        requireNoTraining:
            draft.requireNoTraining,
        requireZeroDataRetention:
            draft.requireZeroDataRetention,
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

export function normalizeModelKey(
    value: string,
): string {
    const normalized =
        value.trim().toLowerCase()

    if (!MODEL_KEY_PATTERN.test(normalized)) {
        throw new Error(
            'Ключ модели может содержать только латинские буквы, цифры и символы . _ : / -; максимум 160 символов.',
        )
    }

    return normalized
}

export function parseModelKeyList(
    value: string,
): string[] {
    const keys = value
        .split(/[\n\r,;]+/)
        .map((item) => item.trim())
        .filter(Boolean)
        .map(normalizeModelKey)

    return Array.from(
        new Set(keys),
    )
}

export function requireText(
    value: string,
    field: string,
): string {
    const normalized = value.trim()

    if (!normalized) {
        throw new Error(
            `${field} не должен быть пустым.`,
        )
    }

    return normalized
}

export function nullableText(
    value: string,
): string | null {
    const normalized = value.trim()
    return normalized || null
}

export function parsePositiveInteger(
    value: string,
    field: string,
): number {
    const parsed = Number(value)

    if (
        !Number.isSafeInteger(parsed)
        || parsed <= 0
    ) {
        throw new Error(
            `${field} должен быть положительным целым числом.`,
        )
    }

    return parsed
}

export function parseOptionalPositiveInteger(
    value: string,
    field: string,
): number | null {
    return value.trim()
        ? parsePositiveInteger(value, field)
        : null
}

export function parseOptionalNonNegativeInteger(
    value: string,
    field: string,
): number | null {
    if (!value.trim()) {
        return null
    }

    const parsed = Number(value)

    if (
        !Number.isSafeInteger(parsed)
        || parsed < 0
    ) {
        throw new Error(
            `${field} должен быть неотрицательным целым числом.`,
        )
    }

    return parsed
}

export function parseOptionalDecimal(
    value: string,
    field: string,
): string | null {
    const normalized = value.trim()

    if (!normalized) {
        return null
    }

    if (!DECIMAL_PATTERN.test(normalized)) {
        throw new Error(
            `${field}: укажите неотрицательное число без экспоненты.`,
        )
    }

    const [integerPart, fractionPart = ''] =
        normalized.split('.')

    const integerDigits =
        integerPart.replace(/^0+/, '').length

    if (
        integerDigits > 18
        || fractionPart.length > 12
    ) {
        throw new Error(
            `${field}: допускается максимум 18 цифр до точки и 12 после.`,
        )
    }

    return normalized
}

export function normalizeJsonObject(
    value: string,
): string {
    const normalized =
        value.trim() || '{}'

    let parsed: unknown

    try {
        parsed = JSON.parse(normalized)
    } catch {
        throw new Error(
            'Дополнительные параметры тарификации должны быть корректным JSON-объектом.',
        )
    }

    if (
        typeof parsed !== 'object'
        || parsed === null
        || Array.isArray(parsed)
    ) {
        throw new Error(
            'Дополнительные параметры тарификации должны быть JSON-объектом.',
        )
    }

    return JSON.stringify(parsed)
}

export function validatePricingDraft(
    status: ModelPricingStatus,
    complete: boolean,
    input: string | null,
    cachedInput: string | null,
    cacheWrite: string | null,
    output: string | null,
    pricingVersion: string | null,
    extraPricingJson: string,
): void {
    if (status === 'UNPRICED') {
        if (
            complete
            || input !== null
            || cachedInput !== null
            || cacheWrite !== null
            || output !== null
            || extraPricingJson !== '{}'
        ) {
            throw new Error(
                'При статусе «Стоимость не указана» ценовые поля должны быть пустыми.',
            )
        }
        return
    }

    if (status === 'FREE') {
        if (
            !complete
            || input === null
            || decimalToScale12(input) !== 0n
            || output === null
            || decimalToScale12(output) !== 0n
            || !isZeroOrNull(cachedInput)
            || !isZeroOrNull(cacheWrite)
            || extraPricingJson !== '{}'
        ) {
            throw new Error(
                'Для бесплатной модели все поля стоимости должны быть нулевыми.',
            )
        }
        return
    }

    if (status === 'CONFIGURED') {
        if (
            !complete
            || input === null
            || output === null
            || !pricingVersion
            || extraPricingJson !== '{}'
        ) {
            throw new Error(
                'Для настроенной стоимости укажите цену входа, цену выхода и версию тарифа; дополнительные JSON-параметры должны быть пустыми.',
            )
        }

        if (
            cachedInput !== null
            && compareDecimalStrings(
                cachedInput,
                input,
            ) > 0
        ) {
            throw new Error(
                'Стоимость кэшированного входа не может превышать обычную стоимость входа.',
            )
        }

        if (
            cacheWrite !== null
            && compareDecimalStrings(
                cacheWrite,
                input,
            ) > 0
        ) {
            throw new Error(
                'Стоимость записи кэша не может превышать обычную стоимость входа.',
            )
        }
        return
    }

    if (complete) {
        throw new Error(
            'Неполная тарификация не может быть отмечена как полностью известная.',
        )
    }
}

export function compareDecimalStrings(
    left: string,
    right: string,
): number {
    const leftScaled =
        decimalToScale12(left)
    const rightScaled =
        decimalToScale12(right)

    if (leftScaled === rightScaled) {
        return 0
    }

    return leftScaled < rightScaled
        ? -1
        : 1
}

export function decimalToScale12(
    value: string,
): bigint {
    const [integerPart, fractionPart = ''] =
        value.split('.')

    const fraction =
        fractionPart.padEnd(12, '0')

    return (
        BigInt(integerPart)
        * 1_000_000_000_000n
    ) + BigInt(fraction || '0')
}

export function isZeroOrNull(
    value: string | null,
): boolean {
    return value === null
        || decimalToScale12(value) === 0n
}
