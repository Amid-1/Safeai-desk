import {
    API_TIMEOUTS,
    apiRequest,
} from './http'
import {
    contractError,
    expectBoolean,
    expectEnum,
    expectInstant,
    expectNonNegativeInteger,
    expectNullableEnum,
    expectNullableInstant,
    expectNullableNonNegativeInteger,
    expectNullableString,
    expectNullableUuid,
    expectRecord,
    expectString,
    expectStringArray,
    expectUuid,
    parseDecimalString,
} from './runtime'
import {
    uuidPathSegment,
} from './query'

const ROUTING_MODES = [
    'SINGLE_PROVIDER_STATIC',
] as const

const RUNTIME_RETENTION_STATUSES = [
    'NOT_DECLARED',
] as const

const HEALTH_STATUSES = [
    'NOT_PROBED',
] as const

const RUNTIME_PRICING_STATUSES = [
    'UNPRICED',
    'FREE',
    'CONFIGURED',
] as const

export const MODEL_LIFECYCLES = [
    'ACTIVE',
    'DEPRECATED',
    'DISABLED',
    'RETIRED',
] as const

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

export const MODEL_ROUTE_OUTCOMES = [
    'ALLOWED',
    'DENIED',
] as const

export const MONTHLY_COST_STATES = [
    'NOT_EVALUATED',
    'KNOWN',
    'UNKNOWN',
] as const

export const MODEL_ROUTE_DECISION_INTEGRITY_VERSIONS = [
    1,
    2,
] as const

export const MODEL_ROUTE_REASONS = [
    'REQUESTED_MODEL',
    'POLICY_DEFAULT',
    'RUNTIME_ONLY_MATCH',
    'LEGACY_RUNTIME_FALLBACK',
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

export type RuntimeModelStatus = {
    provider: string
    model: string
    enabled: boolean
    routingMode: typeof ROUTING_MODES[number]
    maxInputTokens: number
    maxOutputTokens: number
    toolsSupported: boolean
    visionSupported: boolean
    structuredOutputSupported: boolean
    dataRetentionStatus: typeof RUNTIME_RETENTION_STATUSES[number]
    healthStatus: typeof HEALTH_STATUSES[number]
    pricingStatus: typeof RUNTIME_PRICING_STATUSES[number]
    inputUsdPer1mTokens: string | null
    outputUsdPer1mTokens: string | null
    pricingVersion: string | null
}

export type ModelLifecycle =
    typeof MODEL_LIFECYCLES[number]

export type ModelCapability =
    typeof MODEL_CAPABILITIES[number]

export type ModelModality =
    typeof MODEL_MODALITIES[number]

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

export type ModelRouteDecisionIntegrityVersion =
    typeof MODEL_ROUTE_DECISION_INTEGRITY_VERSIONS[number]

export type ModelRouteReason =
    typeof MODEL_ROUTE_REASONS[number]

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
    decisionIntegrityVersion: ModelRouteDecisionIntegrityVersion
    decisionSha256: string
    createdAt: string
}

type RequestOptions = {
    signal?: AbortSignal
}

export async function getRuntimeModelStatus(
    signal?: AbortSignal,
): Promise<RuntimeModelStatus> {
    const value = await apiRequest<unknown>(
        '/api/admin/models/runtime',
        {
            method: 'GET',
            signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseRuntimeModelStatus(value)
}

export async function getModelCatalog(
    options: RequestOptions = {},
): Promise<ModelCatalogEntry[]> {
    const value = await apiRequest<unknown>(
        '/api/admin/models/catalog',
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    if (!Array.isArray(value)) {
        throw contractError(
            'modelCatalog должен быть массивом',
        )
    }

    return value.map(
        (entry, index) =>
            parseModelCatalogEntry(
                entry,
                `modelCatalog[${index}]`,
            ),
    )
}

export async function getEffectiveModelCatalog(
    options: RequestOptions = {},
): Promise<ModelCatalogEntry[]> {
    const value = await apiRequest<unknown>(
        '/api/admin/models/catalog/effective',
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    if (!Array.isArray(value)) {
        throw contractError(
            'effectiveModelCatalog должен быть массивом',
        )
    }

    return value.map(
        (entry, index) =>
            parseModelCatalogEntry(
                entry,
                `effectiveModelCatalog[${index}]`,
            ),
    )
}

export async function createModelCatalogVersion(
    request: CreateModelCatalogVersionRequest,
    options: RequestOptions = {},
): Promise<ModelCatalogEntry> {
    const value = await apiRequest<unknown>(
        '/api/admin/models/catalog',
        {
            method: 'POST',
            json: request,
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseModelCatalogEntry(value)
}

export async function importRuntimeModelCatalog(
    options: RequestOptions = {},
): Promise<ModelCatalogEntry> {
    const value = await apiRequest<unknown>(
        '/api/admin/models/catalog/import-runtime',
        {
            method: 'POST',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseModelCatalogEntry(value)
}

export async function getOrganizationModelPolicy(
    organizationId: string,
    options: RequestOptions = {},
): Promise<OrganizationModelPolicy> {
    const value = await apiRequest<unknown>(
        `/api/admin/models/policies/${uuidPathSegment(organizationId)}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseOrganizationModelPolicy(value)
}

export async function createOrganizationModelPolicyVersion(
    organizationId: string,
    request: CreateOrganizationModelPolicyVersionRequest,
    options: RequestOptions = {},
): Promise<OrganizationModelPolicy> {
    const value = await apiRequest<unknown>(
        `/api/admin/models/policies/${uuidPathSegment(organizationId)}`,
        {
            method: 'POST',
            json: request,
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseOrganizationModelPolicy(value)
}

export async function getModelRouteDecision(
    decisionId: string,
    options: RequestOptions = {},
): Promise<ModelRouteDecision> {
    const value = await apiRequest<unknown>(
        `/api/admin/models/route-decisions/${uuidPathSegment(decisionId)}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseModelRouteDecision(value)
}

export function parseRuntimeModelStatus(
    value: unknown,
    field = 'runtimeModel',
): RuntimeModelStatus {
    const record = expectRecord(value, field)
    const parsed: RuntimeModelStatus = {
        provider: expectString(record.provider, `${field}.provider`, {maxLength: 64}),
        model: expectString(record.model, `${field}.model`, {maxLength: 100}),
        enabled: expectBoolean(record.enabled, `${field}.enabled`),
        routingMode: expectEnum(record.routingMode, `${field}.routingMode`, ROUTING_MODES),
        maxInputTokens: expectNonNegativeInteger(record.maxInputTokens, `${field}.maxInputTokens`),
        maxOutputTokens: expectNonNegativeInteger(record.maxOutputTokens, `${field}.maxOutputTokens`),
        toolsSupported: expectBoolean(record.toolsSupported, `${field}.toolsSupported`),
        visionSupported: expectBoolean(record.visionSupported, `${field}.visionSupported`),
        structuredOutputSupported: expectBoolean(record.structuredOutputSupported, `${field}.structuredOutputSupported`),
        dataRetentionStatus: expectEnum(record.dataRetentionStatus, `${field}.dataRetentionStatus`, RUNTIME_RETENTION_STATUSES),
        healthStatus: expectEnum(record.healthStatus, `${field}.healthStatus`, HEALTH_STATUSES),
        pricingStatus: expectEnum(record.pricingStatus, `${field}.pricingStatus`, RUNTIME_PRICING_STATUSES),
        inputUsdPer1mTokens: parseMoneyString(wireNullable(record.inputUsdPer1mTokens), `${field}.inputUsdPer1mTokens`),
        outputUsdPer1mTokens: parseMoneyString(wireNullable(record.outputUsdPer1mTokens), `${field}.outputUsdPer1mTokens`),
        pricingVersion: expectNullableString(wireNullable(record.pricingVersion), `${field}.pricingVersion`, {maxLength: 64}),
    }
    requirePositive(parsed.maxInputTokens, `${field}.maxInputTokens`)
    requirePositive(parsed.maxOutputTokens, `${field}.maxOutputTokens`)
    validateRuntimePricing(parsed, field)
    return parsed
}

export function parseModelCatalogEntry(
    value: unknown,
    field = 'modelCatalogEntry',
): ModelCatalogEntry {
    const record = expectRecord(value, field)
    const parsed: ModelCatalogEntry = {
        id: expectUuid(record.id, `${field}.id`),
        modelKey: expectString(record.modelKey, `${field}.modelKey`, {maxLength: 160}),
        version: expectNonNegativeInteger(record.version, `${field}.version`),
        provider: expectString(record.provider, `${field}.provider`, {maxLength: 32}),
        providerModelId: expectString(record.providerModelId, `${field}.providerModelId`, {maxLength: 100}),
        displayName: expectString(record.displayName, `${field}.displayName`, {maxLength: 255}),
        lifecycle: expectEnum(record.lifecycle, `${field}.lifecycle`, MODEL_LIFECYCLES),
        maxInputTokens: expectNonNegativeInteger(record.maxInputTokens, `${field}.maxInputTokens`),
        maxOutputTokens: expectNonNegativeInteger(record.maxOutputTokens, `${field}.maxOutputTokens`),
        capabilities: expectStringArray(record.capabilities, `${field}.capabilities`, MODEL_CAPABILITIES),
        inputModalities: expectStringArray(record.inputModalities, `${field}.inputModalities`, MODEL_MODALITIES),
        outputModalities: expectStringArray(record.outputModalities, `${field}.outputModalities`, MODEL_MODALITIES),
        retentionStatus: expectEnum(record.retentionStatus, `${field}.retentionStatus`, MODEL_RETENTION_STATUSES),
        retentionDays: expectNullableNonNegativeInteger(wireNullable(record.retentionDays), `${field}.retentionDays`),
        trainingUseStatus: expectEnum(record.trainingUseStatus, `${field}.trainingUseStatus`, MODEL_TRAINING_USE_STATUSES),
        pricingStatus: expectEnum(record.pricingStatus, `${field}.pricingStatus`, MODEL_PRICING_STATUSES),
        pricingComplete: expectBoolean(record.pricingComplete, `${field}.pricingComplete`),
        inputUsdPer1mTokens: parseMoneyString(wireNullable(record.inputUsdPer1mTokens), `${field}.inputUsdPer1mTokens`),
        cachedInputUsdPer1mTokens: parseMoneyString(wireNullable(record.cachedInputUsdPer1mTokens), `${field}.cachedInputUsdPer1mTokens`),
        cacheWriteInputUsdPer1mTokens: parseMoneyString(wireNullable(record.cacheWriteInputUsdPer1mTokens), `${field}.cacheWriteInputUsdPer1mTokens`),
        outputUsdPer1mTokens: parseMoneyString(wireNullable(record.outputUsdPer1mTokens), `${field}.outputUsdPer1mTokens`),
        extraPricingJson: expectString(record.extraPricingJson, `${field}.extraPricingJson`, {allowEmpty: false, maxLength: 16_000}),
        pricingVersion: expectNullableString(wireNullable(record.pricingVersion), `${field}.pricingVersion`, {maxLength: 64}),
        effectiveFrom: expectInstant(record.effectiveFrom, `${field}.effectiveFrom`),
        source: expectEnum(record.source, `${field}.source`, MODEL_CATALOG_SOURCES),
        createdByUserId: expectUuid(record.createdByUserId, `${field}.createdByUserId`),
        createdAt: expectInstant(record.createdAt, `${field}.createdAt`),
    }
    requirePositive(parsed.version, `${field}.version`)
    requirePositive(parsed.maxInputTokens, `${field}.maxInputTokens`)
    requirePositive(parsed.maxOutputTokens, `${field}.maxOutputTokens`)
    validateCatalogSemantics(parsed, field)
    return parsed
}

export function parseOrganizationModelPolicy(
    value: unknown,
    field = 'organizationModelPolicy',
): OrganizationModelPolicy {
    const record = expectRecord(value, field)
    const parsed: OrganizationModelPolicy = {
        configured: expectBoolean(record.configured, `${field}.configured`),
        id: expectNullableUuid(wireNullable(record.id), `${field}.id`),
        organizationId: expectUuid(record.organizationId, `${field}.organizationId`),
        version: expectNonNegativeInteger(record.version, `${field}.version`),
        enabled: expectBoolean(record.enabled, `${field}.enabled`),
        allowModelKeys: parseModelKeyArray(record.allowModelKeys, `${field}.allowModelKeys`),
        denyModelKeys: parseModelKeyArray(record.denyModelKeys, `${field}.denyModelKeys`),
        defaultModelKey: expectNullableString(wireNullable(record.defaultModelKey), `${field}.defaultModelKey`, {maxLength: 160}),
        maxInputTokens: expectNullableNonNegativeInteger(wireNullable(record.maxInputTokens), `${field}.maxInputTokens`),
        maxOutputTokens: expectNullableNonNegativeInteger(wireNullable(record.maxOutputTokens), `${field}.maxOutputTokens`),
        maxRequestCostUsd: parseMoneyString(wireNullable(record.maxRequestCostUsd), `${field}.maxRequestCostUsd`),
        monthlyBudgetUsd: parseMoneyString(wireNullable(record.monthlyBudgetUsd), `${field}.monthlyBudgetUsd`),
        budgetEnforcement: expectEnum(record.budgetEnforcement, `${field}.budgetEnforcement`, BUDGET_ENFORCEMENTS),
        requireCompletePricing: expectBoolean(record.requireCompletePricing, `${field}.requireCompletePricing`),
        requireNoTraining: expectBoolean(record.requireNoTraining, `${field}.requireNoTraining`),
        requireZeroDataRetention: expectBoolean(record.requireZeroDataRetention, `${field}.requireZeroDataRetention`),
        createdByUserId: expectNullableUuid(wireNullable(record.createdByUserId), `${field}.createdByUserId`),
        createdAt: expectNullableInstant(wireNullable(record.createdAt), `${field}.createdAt`),
    }
    validatePolicySemantics(parsed, field)
    return parsed
}

export function parseModelRouteDecision(
    value: unknown,
    field = 'modelRouteDecision',
): ModelRouteDecision {
    const record = expectRecord(value, field)
    const parsed: ModelRouteDecision = {
        id: expectUuid(record.id, `${field}.id`),
        organizationId: expectUuid(record.organizationId, `${field}.organizationId`),
        userId: expectUuid(record.userId, `${field}.userId`),
        chatId: expectUuid(record.chatId, `${field}.chatId`),
        chatTurnId: expectNullableUuid(wireNullable(record.chatTurnId), `${field}.chatTurnId`),
        clientRequestId: expectUuid(record.clientRequestId, `${field}.clientRequestId`),
        requestContentHash: expectSha256(record.requestContentHash, `${field}.requestContentHash`),
        requestedModelKey: expectNullableString(wireNullable(record.requestedModelKey), `${field}.requestedModelKey`, {maxLength: 160}),
        selectedCatalogEntryId: expectNullableUuid(wireNullable(record.selectedCatalogEntryId), `${field}.selectedCatalogEntryId`),
        selectedCatalogVersion: expectNullableNonNegativeInteger(wireNullable(record.selectedCatalogVersion), `${field}.selectedCatalogVersion`),
        selectedModelKey: expectNullableString(wireNullable(record.selectedModelKey), `${field}.selectedModelKey`, {maxLength: 160}),
        selectedProvider: expectNullableString(wireNullable(record.selectedProvider), `${field}.selectedProvider`, {maxLength: 32}),
        selectedProviderModelId: expectNullableString(wireNullable(record.selectedProviderModelId), `${field}.selectedProviderModelId`, {maxLength: 100}),
        policyId: expectNullableUuid(wireNullable(record.policyId), `${field}.policyId`),
        policyVersion: expectNullableNonNegativeInteger(wireNullable(record.policyVersion), `${field}.policyVersion`),
        requiredCapabilities: expectStringArray(record.requiredCapabilities, `${field}.requiredCapabilities`, MODEL_CAPABILITIES),
        estimatedInputTokens: expectNullableNonNegativeInteger(wireNullable(record.estimatedInputTokens), `${field}.estimatedInputTokens`),
        estimatedOutputTokens: expectNullableNonNegativeInteger(wireNullable(record.estimatedOutputTokens), `${field}.estimatedOutputTokens`),
        estimatedMaxCostUsd: parseMoneyString(wireNullable(record.estimatedMaxCostUsd), `${field}.estimatedMaxCostUsd`),
        monthlyBudgetUsd: parseMoneyString(wireNullable(record.monthlyBudgetUsd), `${field}.monthlyBudgetUsd`),
        monthlySpentUsd: parseMoneyString(wireNullable(record.monthlySpentUsd), `${field}.monthlySpentUsd`),
        monthlyProjectedUsd: parseMoneyString(wireNullable(record.monthlyProjectedUsd), `${field}.monthlyProjectedUsd`),
        monthlyCostKnown: expectBoolean(record.monthlyCostKnown, `${field}.monthlyCostKnown`),
        monthlyCostState: expectEnum(record.monthlyCostState, `${field}.monthlyCostState`, MONTHLY_COST_STATES),
        budgetEnforcement: expectNullableEnum(wireNullable(record.budgetEnforcement), `${field}.budgetEnforcement`, BUDGET_ENFORCEMENTS),
        budgetExceeded: expectBoolean(record.budgetExceeded, `${field}.budgetExceeded`),
        pricingComplete: expectBoolean(record.pricingComplete, `${field}.pricingComplete`),
        outcome: expectEnum(record.outcome, `${field}.outcome`, MODEL_ROUTE_OUTCOMES),
        reason: expectEnum(record.reason, `${field}.reason`, MODEL_ROUTE_REASONS),
        decisionIntegrityVersion: parseDecisionIntegrityVersion(
            record.decisionIntegrityVersion,
            `${field}.decisionIntegrityVersion`,
        ),
        decisionSha256: expectSha256(record.decisionSha256, `${field}.decisionSha256`),
        createdAt: expectInstant(record.createdAt, `${field}.createdAt`),
    }
    validateRouteDecisionSemantics(parsed, field)
    return parsed
}

const ALLOWED_ROUTE_REASONS = new Set<ModelRouteReason>([
    'REQUESTED_MODEL',
    'POLICY_DEFAULT',
    'RUNTIME_ONLY_MATCH',
    'LEGACY_RUNTIME_FALLBACK',
])

const DENIED_ROUTE_REASONS = new Set<ModelRouteReason>([
    'MODEL_NOT_ALLOWED', 'MODEL_DENIED', 'MODEL_NOT_FOUND', 'MODEL_DISABLED',
    'RUNTIME_MISMATCH', 'CAPABILITY_UNSUPPORTED', 'INPUT_LIMIT_EXCEEDED',
    'OUTPUT_LIMIT_EXCEEDED', 'PRICING_INCOMPLETE',
    'TRAINING_POLICY_UNSATISFIED', 'RETENTION_POLICY_UNSATISFIED',
    'REQUEST_COST_LIMIT_EXCEEDED', 'MONTHLY_BUDGET_EXCEEDED',
    'MONTHLY_BUDGET_UNVERIFIABLE',
])

function validateRuntimePricing(value: RuntimeModelStatus, field: string) {
    if (value.pricingStatus === 'UNPRICED') {
        requireContract(!value.inputUsdPer1mTokens && !value.outputUsdPer1mTokens && !value.pricingVersion,
            `${field}: UNPRICED runtime не может содержать pricing values`)
        return
    }
    if (value.pricingStatus === 'FREE') {
        requireContract(isZeroDecimal(value.inputUsdPer1mTokens) && isZeroDecimal(value.outputUsdPer1mTokens),
            `${field}: FREE runtime требует нулевые input/output цены`)
        return
    }
    requireContract(value.inputUsdPer1mTokens !== null && value.outputUsdPer1mTokens !== null,
        `${field}: CONFIGURED runtime требует input/output prices`)
}

function validateCatalogSemantics(value: ModelCatalogEntry, field: string) {
    requireContract(value.inputModalities.length > 0 && value.outputModalities.length > 0,
        `${field}: modalities не должны быть пустыми`)
    if (value.retentionStatus === 'ZERO_DATA_RETENTION') {
        requireContract(value.retentionDays === null || value.retentionDays === 0,
            `${field}: ZERO_DATA_RETENTION требует retentionDays=0|null`)
    }
    switch (value.pricingStatus) {
        case 'UNPRICED':
            requireContract(!value.pricingComplete
                && value.inputUsdPer1mTokens === null
                && value.cachedInputUsdPer1mTokens === null
                && value.cacheWriteInputUsdPer1mTokens === null
                && value.outputUsdPer1mTokens === null
                && value.extraPricingJson === '{}',
            `${field}: нарушена UNPRICED pricing matrix`)
            break
        case 'FREE':
            requireContract(value.pricingComplete
                && isZeroDecimal(value.inputUsdPer1mTokens)
                && isZeroDecimal(value.outputUsdPer1mTokens)
                && (value.cachedInputUsdPer1mTokens === null || isZeroDecimal(value.cachedInputUsdPer1mTokens))
                && (value.cacheWriteInputUsdPer1mTokens === null || isZeroDecimal(value.cacheWriteInputUsdPer1mTokens))
                && value.extraPricingJson === '{}',
            `${field}: нарушена FREE pricing matrix`)
            break
        case 'CONFIGURED':
            requireContract(value.pricingComplete
                && value.inputUsdPer1mTokens !== null
                && value.outputUsdPer1mTokens !== null
                && value.pricingVersion !== null
                && value.extraPricingJson === '{}',
            `${field}: нарушена CONFIGURED pricing matrix`)
            break
        case 'INCOMPLETE':
            requireContract(!value.pricingComplete, `${field}: INCOMPLETE не может быть complete`)
            break
    }
}

function validatePolicySemantics(value: OrganizationModelPolicy, field: string) {
    if (value.configured) {
        requireContract(value.id !== null && value.version > 0
            && value.createdByUserId !== null && value.createdAt !== null,
        `${field}: configured policy требует id/version/creator/createdAt`)
    } else {
        requireContract(value.id === null && value.version === 0
            && value.createdByUserId === null && value.createdAt === null,
        `${field}: unconfigured policy имеет неконсистентную identity`)
    }
    const deny = new Set(value.denyModelKeys)
    requireContract(!value.allowModelKeys.some((key) => deny.has(key)),
        `${field}: allowModelKeys и denyModelKeys пересекаются`)
    if (value.defaultModelKey !== null) {
        requireContract(!deny.has(value.defaultModelKey), `${field}: defaultModelKey находится в denylist`)
        requireContract(value.allowModelKeys.length === 0 || value.allowModelKeys.includes(value.defaultModelKey),
            `${field}: defaultModelKey отсутствует в allowlist`)
    }
}

function validateRouteDecisionSemantics(value: ModelRouteDecision, field: string) {
    requireContract(value.decisionIntegrityVersion === 1 || value.decisionIntegrityVersion === 2,
        `${field}.decisionIntegrityVersion должен быть 1 или 2`)
    requireContract((value.policyId === null) === (value.policyVersion === null),
        `${field}: policyId/policyVersion должны быть парой`)
    requireContract((value.selectedCatalogEntryId === null) === (value.selectedCatalogVersion === null),
        `${field}: catalog id/version должны быть парой`)

    if (value.monthlyBudgetUsd === null) {
        requireContract(
            value.monthlyCostState === 'NOT_EVALUATED'
                && !value.monthlyCostKnown
                && value.monthlySpentUsd === null
                && value.monthlyProjectedUsd === null,
            `${field}: без monthlyBudgetUsd monthly cost должен быть NOT_EVALUATED без spent/projected evidence`,
        )
    } else if (value.monthlyCostKnown) {
        requireContract(value.monthlyCostState === 'KNOWN',
            `${field}: monthlyCostKnown=true требует state=KNOWN`)
    } else {
        requireContract(value.monthlyCostState === 'UNKNOWN',
            `${field}: monthlyCostKnown=false требует state=UNKNOWN`)
    }

    if (value.outcome === 'ALLOWED') {
        requireContract(ALLOWED_ROUTE_REASONS.has(value.reason), `${field}: ALLOWED имеет denied reason`)
        requireContract(value.chatTurnId !== null
            && value.selectedModelKey !== null
            && value.selectedProvider !== null
            && value.selectedProviderModelId !== null
            && value.estimatedInputTokens !== null
            && value.estimatedOutputTokens !== null,
        `${field}: ALLOWED decision не содержит executable metadata`)
    } else {
        requireContract(DENIED_ROUTE_REASONS.has(value.reason), `${field}: DENIED имеет allowed reason`)
        requireContract(value.chatTurnId === null, `${field}: DENIED decision не может иметь chatTurnId`)
    }

    if (value.decisionIntegrityVersion >= 2 && value.reason === 'MODEL_NOT_FOUND') {
        requireContract(value.selectedCatalogEntryId === null
            && value.selectedCatalogVersion === null
            && value.selectedProvider === null
            && value.selectedProviderModelId === null,
        `${field}: MODEL_NOT_FOUND не может содержать physical selection`)
    }
}

function parseDecisionIntegrityVersion(
    value: unknown,
    field: string,
): ModelRouteDecisionIntegrityVersion {
    const parsed =
        expectNonNegativeInteger(
            value,
            field,
        )

    if (
        parsed !== 1
        && parsed !== 2
    ) {
        throw contractError(
            `${field} должен быть 1 или 2`,
        )
    }

    return parsed
}

function parseMoneyString(value: unknown, field: string): string | null {
    if (value === null) {
        return null
    }
    if (typeof value !== 'string') {
        throw contractError(`${field} должен быть decimal string, а не JSON number`)
    }
    return parseDecimalString(value, field)
}

function isZeroDecimal(value: string | null): boolean {
    return value !== null && /^\+?0+(?:\.0+)?$/.test(value)
}

function requirePositive(value: number, field: string) {
    if (value <= 0) {
        throw contractError(`${field} должен быть положительным`)
    }
}

function requireContract(condition: boolean, message: string): asserts condition {
    if (!condition) {
        throw contractError(message)
    }
}

/** Jackson NON_NULL omits nullable fields; normalize omission to null. */
function wireNullable(value: unknown): unknown {
    return value === undefined ? null : value
}

function parseModelKeyArray(value: unknown, field: string): string[] {
    if (!Array.isArray(value)) {
        throw contractError(`${field} должен быть массивом`)
    }
    const result: string[] = []
    value.forEach((item, index) => {
        const key = expectString(item, `${field}[${index}]`, {maxLength: 160})
        if (!result.includes(key)) {
            result.push(key)
        }
    })
    return result
}

function expectSha256(value: unknown, field: string): string {
    const hash = expectString(value, field, {maxLength: 64})
    if (!/^[0-9a-f]{64}$/.test(hash)) {
        throw contractError(`${field} должен быть lowercase SHA-256`)
    }
    return hash
}
