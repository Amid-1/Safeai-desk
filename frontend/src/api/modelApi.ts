// ============================================================
// frontend/src/api/modelApi.ts
// ============================================================
import {
    apiRequest,
} from './http'

export type ModelCapability =
    typeof MODEL_CAPABILITIES[number]

export type ModelModality =
    typeof MODEL_MODALITIES[number]

export type ModelLifecycle =
    typeof MODEL_LIFECYCLES[number]

export type ModelRetentionStatus =
    typeof MODEL_RETENTION_STATUSES[number]

export type ModelTrainingUseStatus =
    typeof MODEL_TRAINING_USE_STATUSES[number]

export type ModelPricingStatus =
    typeof MODEL_PRICING_STATUSES[number]

export type ModelCatalogSource =
    typeof MODEL_CATALOG_SOURCES[number]

export type BudgetEnforcement =
    typeof BUDGET_ENFORCEMENTS[number]

export type ModelRouteOutcome =
    typeof MODEL_ROUTE_OUTCOMES[number]

export type MonthlyCostState =
    typeof MONTHLY_COST_STATES[number]

export type ModelRouteReason =
    'REQUESTED_MODEL'
    | 'POLICY_DEFAULT'
    | 'RUNTIME_ONLY_MATCH'
    | 'LEGACY_RUNTIME_FALLBACK'
    | 'MODEL_NOT_ALLOWED'
    | 'MODEL_DENIED'
    | 'MODEL_NOT_FOUND'
    | 'MODEL_DISABLED'
    | 'RUNTIME_MISMATCH'
    | 'CAPABILITY_UNSUPPORTED'
    | 'INPUT_LIMIT_EXCEEDED'
    | 'OUTPUT_LIMIT_EXCEEDED'
    | 'PRICING_INCOMPLETE'
    | 'TRAINING_POLICY_UNSATISFIED'
    | 'RETENTION_POLICY_UNSATISFIED'
    | 'REQUEST_COST_LIMIT_EXCEEDED'
    | 'MONTHLY_BUDGET_EXCEEDED'
    | 'MONTHLY_BUDGET_UNVERIFIABLE'

export type ModelCatalogEntry = {
    id: string
    modelKey: string
    version: number
    provider: string
    providerModelId: string
    displayName: string
    lifecycle: ModelLifecycle
    maxInputTokens: number
    maxOutputTokens: number
    capabilities: ModelCapability[]
    inputModalities: ModelModality[]
    outputModalities: ModelModality[]
    retentionStatus: ModelRetentionStatus
    retentionDays: number | null
    trainingUseStatus: ModelTrainingUseStatus
    pricingStatus: ModelPricingStatus
    pricingComplete: boolean
    inputUsdPer1mTokens: string | null
    cachedInputUsdPer1mTokens: string | null
    cacheWriteInputUsdPer1mTokens: string | null
    outputUsdPer1mTokens: string | null
    extraPricingJson: string
    pricingVersion: string | null
    effectiveFrom: string
    source: ModelCatalogSource
    createdByUserId: string
    createdAt: string
}

export type RuntimeModelStatus = {
    provider: string
    model: string
    enabled: boolean
    routingMode: string
    maxInputTokens: number
    maxOutputTokens: number
    toolsSupported: boolean
    visionSupported: boolean
    structuredOutputSupported: boolean
    dataRetentionStatus: string
    healthStatus: string
    pricingStatus: string
    inputUsdPer1mTokens: string | null
    outputUsdPer1mTokens: string | null
    pricingVersion: string | null
}

export type OrganizationModelPolicy = {
    configured: boolean
    id: string | null
    organizationId: string
    version: number
    enabled: boolean
    allowModelKeys: string[]
    denyModelKeys: string[]
    defaultModelKey: string | null
    maxInputTokens: number | null
    maxOutputTokens: number | null
    maxRequestCostUsd: string | null
    monthlyBudgetUsd: string | null
    budgetEnforcement: BudgetEnforcement
    requireCompletePricing: boolean
    requireNoTraining: boolean
    requireZeroDataRetention: boolean
    createdByUserId: string | null
    createdAt: string | null
}

export type ModelRouteDecision = {
    id: string
    organizationId: string
    userId: string
    chatId: string
    chatTurnId: string | null
    clientRequestId: string
    requestContentHash: string
    requestedModelKey: string | null
    selectedCatalogEntryId: string | null
    selectedCatalogVersion: number | null
    selectedModelKey: string | null
    selectedProvider: string | null
    selectedProviderModelId: string | null
    policyId: string | null
    policyVersion: number | null
    requiredCapabilities: ModelCapability[]
    inputAccountingVersion: string | null
    additionalInputUnitUpperBound: number | null
    estimatedInputTokens: number | null
    estimatedOutputTokens: number | null
    estimatedMaxCostUsd: string | null
    monthlyBudgetUsd: string | null
    monthlySpentUsd: string | null
    monthlyProjectedUsd: string | null
    monthlyCostKnown: boolean
    monthlyCostState: MonthlyCostState
    budgetEnforcement: BudgetEnforcement | null
    budgetExceeded: boolean
    pricingComplete: boolean
    outcome: ModelRouteOutcome
    reason: ModelRouteReason
    decisionIntegrityVersion: 1 | 2 | 3
    decisionSha256: string
    createdAt: string
}

export type CreateModelCatalogVersionRequest = {
    modelKey: string
    provider: string
    providerModelId: string
    displayName: string
    lifecycle: ModelLifecycle
    maxInputTokens: number
    maxOutputTokens: number
    capabilities: ModelCapability[]
    inputModalities: ModelModality[]
    outputModalities: ModelModality[]
    retentionStatus: ModelRetentionStatus
    retentionDays: number | null
    trainingUseStatus: ModelTrainingUseStatus
    pricingStatus: ModelPricingStatus
    pricingComplete: boolean
    inputUsdPer1mTokens: string | null
    cachedInputUsdPer1mTokens: string | null
    cacheWriteInputUsdPer1mTokens: string | null
    outputUsdPer1mTokens: string | null
    extraPricingJson: string
    pricingVersion: string | null
    effectiveFrom: string | null
    expectedPreviousVersion: number
}

export type CreateOrganizationModelPolicyVersionRequest = {
    expectedPreviousVersion: number
    enabled: boolean
    allowModelKeys: string[]
    denyModelKeys: string[]
    defaultModelKey: string | null
    maxInputTokens: number | null
    maxOutputTokens: number | null
    maxRequestCostUsd: string | null
    monthlyBudgetUsd: string | null
    budgetEnforcement: BudgetEnforcement
    requireCompletePricing: boolean
    requireNoTraining: boolean
    requireZeroDataRetention: boolean
}

export const MODEL_CAPABILITIES = [
    'TOOLS',
    'VISION',
    'STRUCTURED_OUTPUT',
] as const

export const MODEL_MODALITIES = [
    'TEXT',
    'IMAGE',
    'AUDIO',
] as const

export const MODEL_LIFECYCLES = [
    'ACTIVE',
    'DEPRECATED',
    'DISABLED',
    'RETIRED',
] as const

export const MODEL_RETENTION_STATUSES = [
    'NOT_DECLARED',
    'STANDARD',
    'ZERO_DATA_RETENTION',
    'CUSTOM',
] as const

export const MODEL_TRAINING_USE_STATUSES = [
    'NOT_DECLARED',
    'NOT_USED',
    'MAY_BE_USED',
    'CONTRACTUAL_NO_TRAINING',
] as const

export const MODEL_PRICING_STATUSES = [
    'UNPRICED',
    'FREE',
    'CONFIGURED',
    'INCOMPLETE',
] as const

export const MODEL_CATALOG_SOURCES = [
    'MANUAL',
    'RUNTIME_IMPORT',
    'MIGRATED',
] as const

export const BUDGET_ENFORCEMENTS = [
    'SOFT',
    'HARD',
] as const

export const MONTHLY_COST_STATES = [
    'NOT_EVALUATED',
    'KNOWN',
    'UNKNOWN',
] as const

export const MODEL_ROUTE_OUTCOMES = [
    'ALLOWED',
    'DENIED',
] as const

const ALLOWED_REASONS = [
    'REQUESTED_MODEL',
    'POLICY_DEFAULT',
    'RUNTIME_ONLY_MATCH',
    'LEGACY_RUNTIME_FALLBACK',
] as const

const DENIED_REASONS = [
    'MODEL_NOT_ALLOWED',
    'MODEL_DENIED',
    'MODEL_NOT_FOUND',
    'MODEL_DISABLED',
    'RUNTIME_MISMATCH',
    'CAPABILITY_UNSUPPORTED',
    'INPUT_LIMIT_EXCEEDED',
    'OUTPUT_LIMIT_EXCEEDED',
    'PRICING_INCOMPLETE',
    'TRAINING_POLICY_UNSATISFIED',
    'RETENTION_POLICY_UNSATISFIED',
    'REQUEST_COST_LIMIT_EXCEEDED',
    'MONTHLY_BUDGET_EXCEEDED',
    'MONTHLY_BUDGET_UNVERIFIABLE',
] as const

export const MODEL_ROUTE_REASONS = [
    ...ALLOWED_REASONS,
    ...DENIED_REASONS,
] as const

const DECIMAL_PATTERN =
    /^\d+(?:\.\d+)?$/

type ModelApiRequestOptions = {
    signal?: AbortSignal
}

export async function getRuntimeModelStatus(
    signal?: AbortSignal,
): Promise<RuntimeModelStatus> {
    const raw = await apiRequest<unknown>(
        '/api/admin/models/runtime',
        {
            method: 'GET',
            signal,
        },
    )

    return parseRuntimeModelStatus(raw)
}

export async function getModelCatalog(
    options: ModelApiRequestOptions = {},
): Promise<ModelCatalogEntry[]> {
    const raw = await apiRequest<unknown>(
        '/api/admin/models/catalog',
        {
            method: 'GET',
            signal: options.signal,
        },
    )

    return requireArray(raw, 'modelCatalog')
        .map((value, index) =>
            parseModelCatalogEntry(
                value,
                `modelCatalog[${index}]`,
            ),
        )
}

export async function getEffectiveModelCatalog(
    options: ModelApiRequestOptions = {},
): Promise<ModelCatalogEntry[]> {
    const raw = await apiRequest<unknown>(
        '/api/admin/models/catalog/effective',
        {
            method: 'GET',
            signal: options.signal,
        },
    )

    return requireArray(
        raw,
        'effectiveModelCatalog',
    ).map((value, index) =>
        parseModelCatalogEntry(
            value,
            `effectiveModelCatalog[${index}]`,
        ),
    )
}

export async function createModelCatalogVersion(
    request: CreateModelCatalogVersionRequest,
    options: ModelApiRequestOptions = {},
): Promise<ModelCatalogEntry> {
    const raw = await apiRequest<unknown>(
        '/api/admin/models/catalog',
        {
            method: 'POST',
            json: request,
            signal: options.signal,
        },
    )

    return parseModelCatalogEntry(
        raw,
        'modelCatalog',
    )
}

export async function importRuntimeModelCatalog(
    options: ModelApiRequestOptions = {},
): Promise<ModelCatalogEntry> {
    const raw = await apiRequest<unknown>(
        '/api/admin/models/catalog/import-runtime',
        {
            method: 'POST',
            signal: options.signal,
        },
    )

    return parseModelCatalogEntry(
        raw,
        'modelCatalog',
    )
}

export async function getOrganizationModelPolicy(
    organizationId: string,
    options: ModelApiRequestOptions = {},
): Promise<OrganizationModelPolicy> {
    const raw = await apiRequest<unknown>(
        `/api/admin/models/policies/${
            encodeURIComponent(organizationId)
        }`,
        {
            method: 'GET',
            signal: options.signal,
        },
    )

    return parseOrganizationModelPolicy(
        raw,
        'modelPolicy',
    )
}

export async function createOrganizationModelPolicyVersion(
    organizationId: string,
    request: CreateOrganizationModelPolicyVersionRequest,
    options: ModelApiRequestOptions = {},
): Promise<OrganizationModelPolicy> {
    const raw = await apiRequest<unknown>(
        `/api/admin/models/policies/${
            encodeURIComponent(organizationId)
        }`,
        {
            method: 'POST',
            json: request,
            signal: options.signal,
        },
    )

    return parseOrganizationModelPolicy(
        raw,
        'modelPolicy',
    )
}

export async function getModelRouteDecision(
    decisionId: string,
    options: ModelApiRequestOptions = {},
): Promise<ModelRouteDecision> {
    const raw = await apiRequest<unknown>(
        `/api/admin/models/route-decisions/${
            encodeURIComponent(decisionId)
        }`,
        {
            method: 'GET',
            signal: options.signal,
        },
    )

    return parseModelRouteDecision(
        raw,
        'modelRouteDecision',
    )
}

export function parseRuntimeModelStatus(
    value: unknown,
    path = 'runtimeModel',
): RuntimeModelStatus {
    const raw = requireObject(value, path)

    return {
        provider: requireString(
            raw.provider,
            `${path}.provider`,
        ),
        model: requireString(
            raw.model,
            `${path}.model`,
        ),
        enabled: requireBoolean(
            raw.enabled,
            `${path}.enabled`,
        ),
        routingMode: requireString(
            raw.routingMode,
            `${path}.routingMode`,
        ),
        maxInputTokens: requireInteger(
            raw.maxInputTokens,
            `${path}.maxInputTokens`,
        ),
        maxOutputTokens: requireInteger(
            raw.maxOutputTokens,
            `${path}.maxOutputTokens`,
        ),
        toolsSupported: requireBoolean(
            raw.toolsSupported,
            `${path}.toolsSupported`,
        ),
        visionSupported: requireBoolean(
            raw.visionSupported,
            `${path}.visionSupported`,
        ),
        structuredOutputSupported: requireBoolean(
            raw.structuredOutputSupported,
            `${path}.structuredOutputSupported`,
        ),
        dataRetentionStatus: requireString(
            raw.dataRetentionStatus,
            `${path}.dataRetentionStatus`,
        ),
        healthStatus: requireString(
            raw.healthStatus,
            `${path}.healthStatus`,
        ),
        pricingStatus: requireString(
            raw.pricingStatus,
            `${path}.pricingStatus`,
        ),
        inputUsdPer1mTokens:
            optionalDecimalString(
                raw.inputUsdPer1mTokens,
                `${path}.inputUsdPer1mTokens`,
            ),
        outputUsdPer1mTokens:
            optionalDecimalString(
                raw.outputUsdPer1mTokens,
                `${path}.outputUsdPer1mTokens`,
            ),
        pricingVersion:
            optionalString(
                raw.pricingVersion,
                `${path}.pricingVersion`,
            ),
    }
}

export function parseModelCatalogEntry(
    value: unknown,
    path = 'modelCatalog',
): ModelCatalogEntry {
    const raw = requireObject(value, path)

    return {
        id: requireString(raw.id, `${path}.id`),
        modelKey: requireString(
            raw.modelKey,
            `${path}.modelKey`,
        ),
        version: requireInteger(
            raw.version,
            `${path}.version`,
        ),
        provider: requireString(
            raw.provider,
            `${path}.provider`,
        ),
        providerModelId: requireString(
            raw.providerModelId,
            `${path}.providerModelId`,
        ),
        displayName: requireString(
            raw.displayName,
            `${path}.displayName`,
        ),
        lifecycle: requireEnum(
            raw.lifecycle,
            MODEL_LIFECYCLES,
            `${path}.lifecycle`,
        ),
        maxInputTokens: requireInteger(
            raw.maxInputTokens,
            `${path}.maxInputTokens`,
        ),
        maxOutputTokens: requireInteger(
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
        retentionDays: optionalInteger(
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
        inputUsdPer1mTokens:
            optionalDecimalString(
                raw.inputUsdPer1mTokens,
                `${path}.inputUsdPer1mTokens`,
            ),
        cachedInputUsdPer1mTokens:
            optionalDecimalString(
                raw.cachedInputUsdPer1mTokens,
                `${path}.cachedInputUsdPer1mTokens`,
            ),
        cacheWriteInputUsdPer1mTokens:
            optionalDecimalString(
                raw.cacheWriteInputUsdPer1mTokens,
                `${path}.cacheWriteInputUsdPer1mTokens`,
            ),
        outputUsdPer1mTokens:
            optionalDecimalString(
                raw.outputUsdPer1mTokens,
                `${path}.outputUsdPer1mTokens`,
            ),
        extraPricingJson: requireString(
            raw.extraPricingJson,
            `${path}.extraPricingJson`,
        ),
        pricingVersion: optionalString(
            raw.pricingVersion,
            `${path}.pricingVersion`,
        ),
        effectiveFrom: requireString(
            raw.effectiveFrom,
            `${path}.effectiveFrom`,
        ),
        source: requireEnum(
            raw.source,
            MODEL_CATALOG_SOURCES,
            `${path}.source`,
        ),
        createdByUserId: requireString(
            raw.createdByUserId,
            `${path}.createdByUserId`,
        ),
        createdAt: requireString(
            raw.createdAt,
            `${path}.createdAt`,
        ),
    }
}

export function parseOrganizationModelPolicy(
    value: unknown,
    path = 'modelPolicy',
): OrganizationModelPolicy {
    const raw = requireObject(value, path)

    return {
        configured: requireBoolean(
            raw.configured,
            `${path}.configured`,
        ),
        id: optionalString(
            raw.id,
            `${path}.id`,
        ),
        organizationId: requireString(
            raw.organizationId,
            `${path}.organizationId`,
        ),
        version: requireInteger(
            raw.version,
            `${path}.version`,
        ),
        enabled: requireBoolean(
            raw.enabled,
            `${path}.enabled`,
        ),
        allowModelKeys: requireStringArray(
            raw.allowModelKeys,
            `${path}.allowModelKeys`,
        ),
        denyModelKeys: requireStringArray(
            raw.denyModelKeys,
            `${path}.denyModelKeys`,
        ),
        defaultModelKey: optionalString(
            raw.defaultModelKey,
            `${path}.defaultModelKey`,
        ),
        maxInputTokens: optionalInteger(
            raw.maxInputTokens,
            `${path}.maxInputTokens`,
        ),
        maxOutputTokens: optionalInteger(
            raw.maxOutputTokens,
            `${path}.maxOutputTokens`,
        ),
        maxRequestCostUsd:
            optionalDecimalString(
                raw.maxRequestCostUsd,
                `${path}.maxRequestCostUsd`,
            ),
        monthlyBudgetUsd:
            optionalDecimalString(
                raw.monthlyBudgetUsd,
                `${path}.monthlyBudgetUsd`,
            ),
        budgetEnforcement: requireEnum(
            raw.budgetEnforcement,
            BUDGET_ENFORCEMENTS,
            `${path}.budgetEnforcement`,
        ),
        requireCompletePricing: requireBoolean(
            raw.requireCompletePricing,
            `${path}.requireCompletePricing`,
        ),
        requireNoTraining: requireBoolean(
            raw.requireNoTraining,
            `${path}.requireNoTraining`,
        ),
        requireZeroDataRetention: requireBoolean(
            raw.requireZeroDataRetention,
            `${path}.requireZeroDataRetention`,
        ),
        createdByUserId: optionalString(
            raw.createdByUserId,
            `${path}.createdByUserId`,
        ),
        createdAt: optionalString(
            raw.createdAt,
            `${path}.createdAt`,
        ),
    }
}

export function parseModelRouteDecision(
    value: unknown,
    path = 'modelRouteDecision',
): ModelRouteDecision {
    const raw = requireObject(value, path)

    const outcome = requireEnum(
        raw.outcome,
        MODEL_ROUTE_OUTCOMES,
        `${path}.outcome`,
    )

    const reason = requireEnum(
        raw.reason,
        MODEL_ROUTE_REASONS,
        `${path}.reason`,
    )

    const integrity =
        requireInteger(
            raw.decisionIntegrityVersion,
            `${path}.decisionIntegrityVersion`,
        )

    if (integrity !== 1
        && integrity !== 2
        && integrity !== 3) {
        throw new Error(
            `${path}.decisionIntegrityVersion должен быть 1, 2 или 3`,
        )
    }

    const decision: ModelRouteDecision = {
        id: requireString(raw.id, `${path}.id`),
        organizationId: requireString(
            raw.organizationId,
            `${path}.organizationId`,
        ),
        userId: requireString(
            raw.userId,
            `${path}.userId`,
        ),
        chatId: requireString(
            raw.chatId,
            `${path}.chatId`,
        ),
        chatTurnId: optionalString(
            raw.chatTurnId,
            `${path}.chatTurnId`,
        ),
        clientRequestId: requireString(
            raw.clientRequestId,
            `${path}.clientRequestId`,
        ),
        requestContentHash: requireString(
            raw.requestContentHash,
            `${path}.requestContentHash`,
        ),
        requestedModelKey: optionalString(
            raw.requestedModelKey,
            `${path}.requestedModelKey`,
        ),
        selectedCatalogEntryId: optionalString(
            raw.selectedCatalogEntryId,
            `${path}.selectedCatalogEntryId`,
        ),
        selectedCatalogVersion: optionalInteger(
            raw.selectedCatalogVersion,
            `${path}.selectedCatalogVersion`,
        ),
        selectedModelKey: optionalString(
            raw.selectedModelKey,
            `${path}.selectedModelKey`,
        ),
        selectedProvider: optionalString(
            raw.selectedProvider,
            `${path}.selectedProvider`,
        ),
        selectedProviderModelId: optionalString(
            raw.selectedProviderModelId,
            `${path}.selectedProviderModelId`,
        ),
        policyId: optionalString(
            raw.policyId,
            `${path}.policyId`,
        ),
        policyVersion: optionalInteger(
            raw.policyVersion,
            `${path}.policyVersion`,
        ),
        requiredCapabilities: requireEnumArray(
            raw.requiredCapabilities,
            MODEL_CAPABILITIES,
            `${path}.requiredCapabilities`,
        ),
        inputAccountingVersion: optionalString(
            raw.inputAccountingVersion,
            `${path}.inputAccountingVersion`,
        ),
        additionalInputUnitUpperBound: optionalInteger(
            raw.additionalInputUnitUpperBound,
            `${path}.additionalInputUnitUpperBound`,
        ),
        estimatedInputTokens: optionalInteger(
            raw.estimatedInputTokens,
            `${path}.estimatedInputTokens`,
        ),
        estimatedOutputTokens: optionalInteger(
            raw.estimatedOutputTokens,
            `${path}.estimatedOutputTokens`,
        ),
        estimatedMaxCostUsd:
            optionalDecimalString(
                raw.estimatedMaxCostUsd,
                `${path}.estimatedMaxCostUsd`,
            ),
        monthlyBudgetUsd:
            optionalDecimalString(
                raw.monthlyBudgetUsd,
                `${path}.monthlyBudgetUsd`,
            ),
        monthlySpentUsd:
            optionalDecimalString(
                raw.monthlySpentUsd,
                `${path}.monthlySpentUsd`,
            ),
        monthlyProjectedUsd:
            optionalDecimalString(
                raw.monthlyProjectedUsd,
                `${path}.monthlyProjectedUsd`,
            ),
        monthlyCostKnown: requireBoolean(
            raw.monthlyCostKnown,
            `${path}.monthlyCostKnown`,
        ),
        monthlyCostState: requireEnum(
            raw.monthlyCostState,
            MONTHLY_COST_STATES,
            `${path}.monthlyCostState`,
        ),
        budgetEnforcement:
            raw.budgetEnforcement === null
            || raw.budgetEnforcement === undefined
                ? null
                : requireEnum(
                    raw.budgetEnforcement,
                    BUDGET_ENFORCEMENTS,
                    `${path}.budgetEnforcement`,
                ),
        budgetExceeded: requireBoolean(
            raw.budgetExceeded,
            `${path}.budgetExceeded`,
        ),
        pricingComplete: requireBoolean(
            raw.pricingComplete,
            `${path}.pricingComplete`,
        ),
        outcome,
        reason,
        decisionIntegrityVersion: integrity,
        decisionSha256: requireString(
            raw.decisionSha256,
            `${path}.decisionSha256`,
        ),
        createdAt: requireString(
            raw.createdAt,
            `${path}.createdAt`,
        ),
    }

    validateRouteSemantics(
        decision,
        path,
    )

    return decision
}

function validateRouteSemantics(
    decision: ModelRouteDecision,
    path: string,
): void {
    const reasonSet =
        decision.outcome === 'ALLOWED'
            ? ALLOWED_REASONS
            : DENIED_REASONS

    if (!reasonSet.includes(
        decision.reason as never,
    )) {
        throw new Error(
            `${path}.reason не соответствует outcome`,
        )
    }

    if (decision.outcome === 'ALLOWED') {
        if (
            decision.chatTurnId === null
            || decision.selectedModelKey === null
            || decision.selectedProvider === null
            || decision.selectedProviderModelId === null
            || decision.estimatedInputTokens === null
            || decision.estimatedOutputTokens === null
        ) {
            throw new Error(
                'ALLOWED decision не содержит executable metadata',
            )
        }
    } else if (decision.chatTurnId !== null) {
        throw new Error(
            'DENIED decision не должен содержать chatTurnId',
        )
    }

    if (decision.reason === 'MODEL_NOT_FOUND'
        && decision.decisionIntegrityVersion >= 2
        && (
            decision.selectedCatalogEntryId !== null
            || decision.selectedCatalogVersion !== null
            || decision.selectedProvider !== null
            || decision.selectedProviderModelId !== null
        )) {
        throw new Error(
            'MODEL_NOT_FOUND decision содержит physical target',
        )
    }

    if (decision.decisionIntegrityVersion === 3
        && (
            decision.inputAccountingVersion === null
            || decision.additionalInputUnitUpperBound === null
            || decision.additionalInputUnitUpperBound < 0
        )) {
        throw new Error(
            'V3 decision не содержит input accounting provenance',
        )
    }

    if (decision.decisionIntegrityVersion < 3
        && (
            decision.inputAccountingVersion !== null
            || decision.additionalInputUnitUpperBound !== null
        )) {
        throw new Error(
            'V1/V2 decision неожиданно содержит V3 provenance',
        )
    }

    if (decision.monthlyCostState === 'NOT_EVALUATED'
        && decision.monthlyBudgetUsd !== null) {
        throw new Error(
            'NOT_EVALUATED monthly cost не должен иметь monthlyBudgetUsd',
        )
    }

    if (decision.monthlyCostState === 'KNOWN'
        && !decision.monthlyCostKnown) {
        throw new Error(
            'KNOWN monthly cost требует monthlyCostKnown=true',
        )
    }

    if (decision.monthlyCostState === 'UNKNOWN'
        && decision.monthlyCostKnown) {
        throw new Error(
            'UNKNOWN monthly cost требует monthlyCostKnown=false',
        )
    }
}

function optionalDecimalString(
    value: unknown,
    path: string,
): string | null {
    if (value === null || value === undefined) {
        return null
    }

    if (
        typeof value !== 'string'
        || !DECIMAL_PATTERN.test(value)
    ) {
        throw new Error(
            `${path} должен быть decimal string`,
        )
    }

    return value
}

function optionalString(
    value: unknown,
    path: string,
): string | null {
    if (value === null || value === undefined) {
        return null
    }

    return requireString(value, path)
}

function optionalInteger(
    value: unknown,
    path: string,
): number | null {
    if (value === null || value === undefined) {
        return null
    }

    return requireInteger(value, path)
}

function requireObject(
    value: unknown,
    path: string,
): Record<string, unknown> {
    if (
        value === null
        || typeof value !== 'object'
        || Array.isArray(value)
    ) {
        throw new Error(
            `${path} должен быть object`,
        )
    }

    return value as Record<string, unknown>
}

function requireArray(
    value: unknown,
    path: string,
): unknown[] {
    if (!Array.isArray(value)) {
        throw new Error(
            `${path} должен быть array`,
        )
    }

    return value
}

function requireString(
    value: unknown,
    path: string,
): string {
    if (typeof value !== 'string') {
        throw new Error(
            `${path} должен быть string`,
        )
    }

    return value
}

function requireBoolean(
    value: unknown,
    path: string,
): boolean {
    if (typeof value !== 'boolean') {
        throw new Error(
            `${path} должен быть boolean`,
        )
    }

    return value
}

function requireInteger(
    value: unknown,
    path: string,
): number {
    if (
        typeof value !== 'number'
        || !Number.isSafeInteger(value)
    ) {
        throw new Error(
            `${path} должен быть safe integer`,
        )
    }

    return value
}

function requireStringArray(
    value: unknown,
    path: string,
): string[] {
    if (
        !Array.isArray(value)
        || value.some(
            item => typeof item !== 'string',
        )
    ) {
        throw new Error(
            `${path} должен быть string[]`,
        )
    }

    return [...value] as string[]
}

function requireEnumArray<
    T extends readonly string[],
>(
    value: unknown,
    allowed: T,
    path: string,
): T[number][] {
    if (!Array.isArray(value)) {
        throw new Error(
            `${path} должен быть array`,
        )
    }

    return value.map(
        (item, index) =>
            requireEnum(
                item,
                allowed,
                `${path}[${index}]`,
            ),
    )
}

function requireEnum<
    T extends readonly string[],
>(
    value: unknown,
    allowed: T,
    path: string,
): T[number] {
    if (
        typeof value !== 'string'
        || !allowed.includes(
            value as T[number],
        )
    ) {
        throw new Error(
            `${path} содержит неизвестное значение`,
        )
    }

    return value as T[number]
}
