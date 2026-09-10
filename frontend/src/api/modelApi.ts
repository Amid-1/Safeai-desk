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
    | 'AMBIGUOUS_RUNTIME_MAPPING'
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

export type RuntimeRoutingMode =
    typeof RUNTIME_ROUTING_MODES[number]

export type RuntimeDataRetentionStatus =
    typeof RUNTIME_DATA_RETENTION_STATUSES[number]

export type RuntimeHealthStatus =
    typeof RUNTIME_HEALTH_STATUSES[number]

export type RuntimePricingStatus =
    typeof RUNTIME_PRICING_STATUSES[number]

export type InputAccountingVersion =
    typeof INPUT_ACCOUNTING_VERSIONS[number]

export type RuntimeModelStatus = {
    provider: string
    model: string
    enabled: boolean
    routingMode: RuntimeRoutingMode
    maxInputTokens: number
    maxOutputTokens: number
    toolsSupported: boolean
    visionSupported: boolean
    structuredOutputSupported: boolean
    dataRetentionStatus: RuntimeDataRetentionStatus
    healthStatus: RuntimeHealthStatus
    pricingStatus: RuntimePricingStatus
    inputUsdPer1mTokens: string | null
    outputUsdPer1mTokens: string | null
    pricingVersion: string | null
}


export const RUNTIME_MODEL_PROBE_STATUSES = [
    'AVAILABLE',
    'AUTH_ERROR',
    'RATE_LIMITED',
    'MODEL_NOT_FOUND',
    'UNAVAILABLE',
    'CONFIGURATION_MISMATCH',
    'ERROR',
] as const

export type RuntimeModelProbeStatus =
    typeof RUNTIME_MODEL_PROBE_STATUSES[number]

export const RUNTIME_ROUTING_MODES = [
    'SINGLE_PROVIDER_STATIC',
] as const

export const RUNTIME_DATA_RETENTION_STATUSES = [
    'NOT_DECLARED',
    'STANDARD',
    'ZERO_DATA_RETENTION',
    'CUSTOM',
] as const

export const RUNTIME_HEALTH_STATUSES = [
    'NOT_PROBED',
    'AVAILABLE',
    'UNAVAILABLE',
] as const

export const RUNTIME_PRICING_STATUSES = [
    'UNPRICED',
    'FREE',
    'CONFIGURED',
] as const

export const INPUT_ACCOUNTING_VERSIONS = [
    'UTF8_STRUCTURAL_UNITS_V2',
] as const

export type RuntimeModelProbe = {
    provider: string
    model: string
    status: RuntimeModelProbeStatus
    checkedAt: string
    latencyMs: number
    httpStatus: number | null
    message: string
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
    inputAccountingVersion: InputAccountingVersion | null
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

export type ModelPolicyPreviewItem = {
    modelKey: string
    catalogVersion: number
    provider: string
    providerModelId: string
    lifecycle: ModelLifecycle
    outcome: ModelRouteOutcome
    reason: ModelRouteReason | null
    effectiveInputLimit: number
    effectiveOutputLimit: number
    pricingComplete: boolean
    estimatedMaxCostUsd: string | null
    monthlyBudgetUsd: string | null
    monthlyProjectedUsd: string | null
    monthlyCostKnown: boolean
    budgetExceeded: boolean
}

export type ModelPolicyPreview = {
    organizationId: string
    basePolicyVersion: number
    enabled: boolean
    evaluatedAt: string
    runtimeProvider: string
    runtimeModel: string
    automaticOutcome: ModelRouteOutcome
    automaticReason: ModelRouteReason
    automaticModelKey: string | null
    executableModelCount: number
    wouldLockOutOrganization: boolean
    models: ModelPolicyPreviewItem[]
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
    'AMBIGUOUS_RUNTIME_MAPPING',
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

export async function probeRuntimeModel(
    options: ModelApiRequestOptions = {},
): Promise<RuntimeModelProbe> {
    const raw = await apiRequest<unknown>(
        '/api/admin/models/runtime/probe',
        {
            method: 'POST',
            signal: options.signal,
        },
    )

    return parseRuntimeModelProbe(raw)
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
    const validatedOrganizationId =
        requireUuid(organizationId, 'organizationId')
    const raw = await apiRequest<unknown>(
        `/api/admin/models/policies/${
            encodeURIComponent(validatedOrganizationId)
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
    const validatedOrganizationId =
        requireUuid(organizationId, 'organizationId')
    const raw = await apiRequest<unknown>(
        `/api/admin/models/policies/${
            encodeURIComponent(validatedOrganizationId)
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

export async function previewOrganizationModelPolicy(
    organizationId: string,
    request: CreateOrganizationModelPolicyVersionRequest,
    options: ModelApiRequestOptions = {},
): Promise<ModelPolicyPreview> {
    const validatedOrganizationId =
        requireUuid(organizationId, 'organizationId')
    const raw = await apiRequest<unknown>(
        `/api/admin/models/policies/${
            encodeURIComponent(validatedOrganizationId)
        }/preview`,
        {
            method: 'POST',
            json: request,
            signal: options.signal,
        },
    )

    return parseModelPolicyPreview(
        raw,
        'modelPolicyPreview',
    )
}

export async function getModelRouteDecision(
    decisionId: string,
    options: ModelApiRequestOptions = {},
): Promise<ModelRouteDecision> {
    const validatedDecisionId =
        requireUuid(decisionId, 'decisionId')
    const raw = await apiRequest<unknown>(
        `/api/admin/models/route-decisions/${
            encodeURIComponent(validatedDecisionId)
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
        provider: requireNonBlankString(
            raw.provider,
            `${path}.provider`,
        ),
        model: requireNonBlankString(
            raw.model,
            `${path}.model`,
        ),
        enabled: requireBoolean(
            raw.enabled,
            `${path}.enabled`,
        ),
        routingMode: requireEnum(
            raw.routingMode,
            RUNTIME_ROUTING_MODES,
            `${path}.routingMode`,
        ),
        maxInputTokens: requirePositiveInteger(
            raw.maxInputTokens,
            `${path}.maxInputTokens`,
        ),
        maxOutputTokens: requirePositiveInteger(
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
        dataRetentionStatus: requireEnum(
            raw.dataRetentionStatus,
            RUNTIME_DATA_RETENTION_STATUSES,
            `${path}.dataRetentionStatus`,
        ),
        healthStatus: requireEnum(
            raw.healthStatus,
            RUNTIME_HEALTH_STATUSES,
            `${path}.healthStatus`,
        ),
        pricingStatus: requireEnum(
            raw.pricingStatus,
            RUNTIME_PRICING_STATUSES,
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
            optionalNonBlankString(
                raw.pricingVersion,
                `${path}.pricingVersion`,
            ),
    }
}

export function parseRuntimeModelProbe(
    value: unknown,
    path = 'runtimeModelProbe',
): RuntimeModelProbe {
    const raw = requireObject(value, path)

    const latencyMs = requireInteger(
        raw.latencyMs,
        `${path}.latencyMs`,
    )

    if (latencyMs < 0) {
        throw new Error(
            `${path}.latencyMs не может быть отрицательным`,
        )
    }

    const httpStatus =
        raw.httpStatus === null
        || raw.httpStatus === undefined
            ? null
            : requireInteger(
                raw.httpStatus,
                `${path}.httpStatus`,
            )

    if (
        httpStatus !== null
        && (
            httpStatus < 100
            || httpStatus > 599
        )
    ) {
        throw new Error(
            `${path}.httpStatus должен быть 100..599`,
        )
    }

    return {
        provider: requireNonBlankString(
            raw.provider,
            `${path}.provider`,
        ),
        model: requireNonBlankString(
            raw.model,
            `${path}.model`,
        ),
        status: requireEnum(
            raw.status,
            RUNTIME_MODEL_PROBE_STATUSES,
            `${path}.status`,
        ),
        checkedAt: requireInstant(
            raw.checkedAt,
            `${path}.checkedAt`,
        ),
        latencyMs,
        httpStatus,
        message: requireString(
            raw.message,
            `${path}.message`,
        ),
    }
}

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

export function parseOrganizationModelPolicy(
    value: unknown,
    path = 'modelPolicy',
): OrganizationModelPolicy {
    const raw = requireObject(value, path)

    const policy: OrganizationModelPolicy = {
        configured: requireBoolean(
            raw.configured,
            `${path}.configured`,
        ),
        id: optionalUuid(
            raw.id,
            `${path}.id`,
        ),
        organizationId: requireUuid(
            raw.organizationId,
            `${path}.organizationId`,
        ),
        version: requireNonNegativeInteger(
            raw.version,
            `${path}.version`,
        ),
        enabled: requireBoolean(
            raw.enabled,
            `${path}.enabled`,
        ),
        allowModelKeys: requireModelKeyArray(
            raw.allowModelKeys,
            `${path}.allowModelKeys`,
        ),
        denyModelKeys: requireModelKeyArray(
            raw.denyModelKeys,
            `${path}.denyModelKeys`,
        ),
        defaultModelKey: optionalModelKey(
            raw.defaultModelKey,
            `${path}.defaultModelKey`,
        ),
        maxInputTokens: optionalPositiveInteger(
            raw.maxInputTokens,
            `${path}.maxInputTokens`,
        ),
        maxOutputTokens: optionalPositiveInteger(
            raw.maxOutputTokens,
            `${path}.maxOutputTokens`,
        ),
        maxRequestCostUsd: optionalDecimalString(
            raw.maxRequestCostUsd,
            `${path}.maxRequestCostUsd`,
        ),
        monthlyBudgetUsd: optionalDecimalString(
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
        createdByUserId: optionalUuid(
            raw.createdByUserId,
            `${path}.createdByUserId`,
        ),
        createdAt: optionalInstant(
            raw.createdAt,
            `${path}.createdAt`,
        ),
    }

    validateOrganizationPolicySemantics(policy, path)
    return policy
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
        id: requireUuid(raw.id, `${path}.id`),
        organizationId: requireUuid(
            raw.organizationId,
            `${path}.organizationId`,
        ),
        userId: requireUuid(
            raw.userId,
            `${path}.userId`,
        ),
        chatId: requireUuid(
            raw.chatId,
            `${path}.chatId`,
        ),
        chatTurnId: optionalUuid(
            raw.chatTurnId,
            `${path}.chatTurnId`,
        ),
        clientRequestId: requireUuid(
            raw.clientRequestId,
            `${path}.clientRequestId`,
        ),
        requestContentHash: requireSha256(
            raw.requestContentHash,
            `${path}.requestContentHash`,
        ),
        requestedModelKey: optionalModelKey(
            raw.requestedModelKey,
            `${path}.requestedModelKey`,
        ),
        selectedCatalogEntryId: optionalUuid(
            raw.selectedCatalogEntryId,
            `${path}.selectedCatalogEntryId`,
        ),
        selectedCatalogVersion: optionalPositiveInteger(
            raw.selectedCatalogVersion,
            `${path}.selectedCatalogVersion`,
        ),
        selectedModelKey: optionalModelKey(
            raw.selectedModelKey,
            `${path}.selectedModelKey`,
        ),
        selectedProvider: optionalNonBlankString(
            raw.selectedProvider,
            `${path}.selectedProvider`,
        ),
        selectedProviderModelId: optionalNonBlankString(
            raw.selectedProviderModelId,
            `${path}.selectedProviderModelId`,
        ),
        policyId: optionalUuid(
            raw.policyId,
            `${path}.policyId`,
        ),
        policyVersion: optionalPositiveInteger(
            raw.policyVersion,
            `${path}.policyVersion`,
        ),
        requiredCapabilities: requireEnumArray(
            raw.requiredCapabilities,
            MODEL_CAPABILITIES,
            `${path}.requiredCapabilities`,
        ),
        inputAccountingVersion: optionalEnum(
            raw.inputAccountingVersion,
            INPUT_ACCOUNTING_VERSIONS,
            `${path}.inputAccountingVersion`,
        ),
        additionalInputUnitUpperBound: optionalNonNegativeInteger(
            raw.additionalInputUnitUpperBound,
            `${path}.additionalInputUnitUpperBound`,
        ),
        estimatedInputTokens: optionalNonNegativeInteger(
            raw.estimatedInputTokens,
            `${path}.estimatedInputTokens`,
        ),
        estimatedOutputTokens: optionalNonNegativeInteger(
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
        decisionSha256: requireSha256(
            raw.decisionSha256,
            `${path}.decisionSha256`,
        ),
        createdAt: requireInstant(
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

export function parseModelPolicyPreview(
    value: unknown,
    path = 'modelPolicyPreview',
): ModelPolicyPreview {
    const raw = requireObject(value, path)
    const models = requireArray(raw.models, `${path}.models`)
        .map((item, index): ModelPolicyPreviewItem => {
            const model = requireObject(
                item,
                `${path}.models[${index}]`,
            )
            return {
                modelKey: requireModelKey(
                    model.modelKey,
                    `${path}.models[${index}].modelKey`,
                ),
                catalogVersion: requirePositiveInteger(
                    model.catalogVersion,
                    `${path}.models[${index}].catalogVersion`,
                ),
                provider: requireNonBlankString(
                    model.provider,
                    `${path}.models[${index}].provider`,
                ),
                providerModelId: requireNonBlankString(
                    model.providerModelId,
                    `${path}.models[${index}].providerModelId`,
                ),
                lifecycle: requireEnum(
                    model.lifecycle,
                    MODEL_LIFECYCLES,
                    `${path}.models[${index}].lifecycle`,
                ),
                outcome: requireEnum(
                    model.outcome,
                    MODEL_ROUTE_OUTCOMES,
                    `${path}.models[${index}].outcome`,
                ),
                reason: optionalEnum(
                    model.reason,
                    MODEL_ROUTE_REASONS,
                    `${path}.models[${index}].reason`,
                ),
                effectiveInputLimit: requirePositiveInteger(
                    model.effectiveInputLimit,
                    `${path}.models[${index}].effectiveInputLimit`,
                ),
                effectiveOutputLimit: requirePositiveInteger(
                    model.effectiveOutputLimit,
                    `${path}.models[${index}].effectiveOutputLimit`,
                ),
                pricingComplete: requireBoolean(
                    model.pricingComplete,
                    `${path}.models[${index}].pricingComplete`,
                ),
                estimatedMaxCostUsd: optionalDecimalString(
                    model.estimatedMaxCostUsd,
                    `${path}.models[${index}].estimatedMaxCostUsd`,
                ),
                monthlyBudgetUsd: optionalDecimalString(
                    model.monthlyBudgetUsd,
                    `${path}.models[${index}].monthlyBudgetUsd`,
                ),
                monthlyProjectedUsd: optionalDecimalString(
                    model.monthlyProjectedUsd,
                    `${path}.models[${index}].monthlyProjectedUsd`,
                ),
                monthlyCostKnown: requireBoolean(
                    model.monthlyCostKnown,
                    `${path}.models[${index}].monthlyCostKnown`,
                ),
                budgetExceeded: requireBoolean(
                    model.budgetExceeded,
                    `${path}.models[${index}].budgetExceeded`,
                ),
            }
        })

    const result: ModelPolicyPreview = {
        organizationId: requireUuid(
            raw.organizationId,
            `${path}.organizationId`,
        ),
        basePolicyVersion: requireNonNegativeInteger(
            raw.basePolicyVersion,
            `${path}.basePolicyVersion`,
        ),
        enabled: requireBoolean(
            raw.enabled,
            `${path}.enabled`,
        ),
        evaluatedAt: requireInstant(
            raw.evaluatedAt,
            `${path}.evaluatedAt`,
        ),
        runtimeProvider: requireNonBlankString(
            raw.runtimeProvider,
            `${path}.runtimeProvider`,
        ),
        runtimeModel: requireNonBlankString(
            raw.runtimeModel,
            `${path}.runtimeModel`,
        ),
        automaticOutcome: requireEnum(
            raw.automaticOutcome,
            MODEL_ROUTE_OUTCOMES,
            `${path}.automaticOutcome`,
        ),
        automaticReason: requireEnum(
            raw.automaticReason,
            MODEL_ROUTE_REASONS,
            `${path}.automaticReason`,
        ),
        automaticModelKey: optionalModelKey(
            raw.automaticModelKey,
            `${path}.automaticModelKey`,
        ),
        executableModelCount: requireNonNegativeInteger(
            raw.executableModelCount,
            `${path}.executableModelCount`,
        ),
        wouldLockOutOrganization: requireBoolean(
            raw.wouldLockOutOrganization,
            `${path}.wouldLockOutOrganization`,
        ),
        models,
    }

    const modelKeys = models.map(item => item.modelKey)
    if (new Set(modelKeys).size !== modelKeys.length) {
        throw new Error(`${path}.models содержит duplicate modelKey`)
    }

    for (const [index, item] of models.entries()) {
        if (item.outcome === 'ALLOWED' && item.reason !== null) {
            throw new Error(
                `${path}.models[${index}] ALLOWED не должен иметь denial reason`,
            )
        }
        if (item.outcome === 'DENIED'
            && (item.reason === null
                || !DENIED_REASONS.includes(item.reason as never))) {
            throw new Error(
                `${path}.models[${index}] DENIED требует denial reason`,
            )
        }
    }

    const automaticReasonSet =
        result.automaticOutcome === 'ALLOWED'
            ? ALLOWED_REASONS
            : DENIED_REASONS
    if (!automaticReasonSet.includes(result.automaticReason as never)) {
        throw new Error(
            `${path}.automaticReason не соответствует automaticOutcome`,
        )
    }
    if (result.automaticOutcome === 'ALLOWED'
        && result.automaticModelKey === null) {
        throw new Error(
            `${path}.automaticModelKey обязателен для ALLOWED`,
        )
    }

    if (result.executableModelCount
        !== models.filter(item => item.outcome === 'ALLOWED').length) {
        throw new Error(
            `${path}.executableModelCount не соответствует models`,
        )
    }
    if (result.wouldLockOutOrganization
        !== (result.enabled && result.executableModelCount === 0)) {
        throw new Error(
            `${path}.wouldLockOutOrganization противоречит preview`,
        )
    }
    return result
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

const UUID_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const SHA256_PATTERN = /^[0-9a-f]{64}$/
const MODEL_KEY_PATTERN = /^[a-z0-9][a-z0-9._:/-]{0,159}$/
const INSTANT_PATTERN =
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/

function requireNonBlankString(
    value: unknown,
    path: string,
): string {
    const result = requireString(value, path)
    if (!result.trim()) {
        throw new Error(`${path} не должен быть пустым`)
    }
    return result
}

function optionalNonBlankString(
    value: unknown,
    path: string,
): string | null {
    return value === null || value === undefined
        ? null
        : requireNonBlankString(value, path)
}

function requireUuid(value: unknown, path: string): string {
    const result = requireString(value, path)
    if (!UUID_PATTERN.test(result)) {
        throw new Error(`${path} должен быть UUID`)
    }
    return result
}

function optionalUuid(value: unknown, path: string): string | null {
    return value === null || value === undefined
        ? null
        : requireUuid(value, path)
}

function requireSha256(value: unknown, path: string): string {
    const result = requireString(value, path)
    if (!SHA256_PATTERN.test(result)) {
        throw new Error(`${path} должен быть lowercase SHA-256`)
    }
    return result
}

function requireInstant(value: unknown, path: string): string {
    const result = requireString(value, path)
    const match = INSTANT_PATTERN.exec(result)

    if (!match) {
        throw new Error(`${path} должен быть ISO-8601 Instant в UTC`)
    }

    const year = Number(result.slice(0, 4))
    const month = Number(result.slice(5, 7))
    const day = Number(result.slice(8, 10))
    const hour = Number(result.slice(11, 13))
    const minute = Number(result.slice(14, 16))
    const second = Number(result.slice(17, 19))

    if (month < 1 || month > 12
        || day < 1
        || day > daysInUtcMonth(year, month)
        || hour > 23
        || minute > 59
        || second > 59
        || Number.isNaN(Date.parse(result))) {
        throw new Error(`${path} должен быть корректным ISO-8601 Instant в UTC`)
    }

    return result
}

function daysInUtcMonth(year: number, month: number): number {
    if (month === 2) {
        const leap = year % 4 === 0
            && (year % 100 !== 0 || year % 400 === 0)
        return leap ? 29 : 28
    }

    return [4, 6, 9, 11].includes(month)
        ? 30
        : 31
}

function optionalInstant(value: unknown, path: string): string | null {
    return value === null || value === undefined
        ? null
        : requireInstant(value, path)
}

function requirePositiveInteger(value: unknown, path: string): number {
    const result = requireInteger(value, path)
    if (result <= 0) {
        throw new Error(`${path} должен быть положительным safe integer`)
    }
    return result
}

function requireNonNegativeInteger(value: unknown, path: string): number {
    const result = requireInteger(value, path)
    if (result < 0) {
        throw new Error(`${path} должен быть неотрицательным safe integer`)
    }
    return result
}

function optionalPositiveInteger(value: unknown, path: string): number | null {
    return value === null || value === undefined
        ? null
        : requirePositiveInteger(value, path)
}

function optionalNonNegativeInteger(value: unknown, path: string): number | null {
    return value === null || value === undefined
        ? null
        : requireNonNegativeInteger(value, path)
}

function requireModelKey(value: unknown, path: string): string {
    const result = requireString(value, path)
    if (!MODEL_KEY_PATTERN.test(result) || result !== result.toLowerCase()) {
        throw new Error(`${path} должен быть нормализованным modelKey`)
    }
    return result
}

function optionalModelKey(value: unknown, path: string): string | null {
    return value === null || value === undefined
        ? null
        : requireModelKey(value, path)
}

function requireModelKeyArray(value: unknown, path: string): string[] {
    const result = requireArray(value, path).map(
        (item, index) => requireModelKey(item, `${path}[${index}]`),
    )
    if (new Set(result).size !== result.length) {
        throw new Error(`${path} содержит дубликаты`)
    }
    return result
}

function optionalEnum<T extends readonly string[]>(
    value: unknown,
    allowed: T,
    path: string,
): T[number] | null {
    return value === null || value === undefined
        ? null
        : requireEnum(value, allowed, path)
}

function requireJsonObjectString(value: unknown, path: string): string {
    const result = requireString(value, path)

    let parsed: unknown
    try {
        parsed = JSON.parse(result) as unknown
    } catch {
        throw new Error(`${path} должен быть JSON object string`)
    }

    if (
        parsed === null
        || typeof parsed !== 'object'
        || Array.isArray(parsed)
    ) {
        throw new Error(`${path} должен быть JSON object string`)
    }

    return result
}

function validateOrganizationPolicySemantics(
    policy: OrganizationModelPolicy,
    path: string,
): void {
    const overlap = policy.allowModelKeys.filter(
        key => policy.denyModelKeys.includes(key),
    )
    if (overlap.length > 0) {
        throw new Error(`${path} allow/deny lists пересекаются`)
    }
    if (policy.defaultModelKey !== null
        && policy.denyModelKeys.includes(policy.defaultModelKey)) {
        throw new Error(`${path}.defaultModelKey находится в denylist`)
    }
    if (policy.defaultModelKey !== null
        && policy.allowModelKeys.length > 0
        && !policy.allowModelKeys.includes(policy.defaultModelKey)) {
        throw new Error(`${path}.defaultModelKey отсутствует в allowlist`)
    }

    if (!policy.configured) {
        const validSynthetic =
            policy.id === null
            && policy.version === 0
            && !policy.enabled
            && policy.allowModelKeys.length === 0
            && policy.denyModelKeys.length === 0
            && policy.defaultModelKey === null
            && policy.maxInputTokens === null
            && policy.maxOutputTokens === null
            && policy.maxRequestCostUsd === null
            && policy.monthlyBudgetUsd === null
            && policy.budgetEnforcement === 'SOFT'
            && !policy.requireCompletePricing
            && !policy.requireNoTraining
            && !policy.requireZeroDataRetention
            && policy.createdByUserId === null
            && policy.createdAt === null
        if (!validSynthetic) {
            throw new Error(`${path} содержит некорректное unconfigured состояние`)
        }
        return
    }

    if (policy.id === null
        || policy.version <= 0
        || policy.createdByUserId === null
        || policy.createdAt === null) {
        throw new Error(`${path} содержит неполное configured состояние`)
    }
}

function validateCatalogSemantics(
    entry: ModelCatalogEntry,
    path: string,
): void {
    if (entry.inputModalities.length === 0 || entry.outputModalities.length === 0) {
        throw new Error(`${path}.modalities не должны быть пустыми`)
    }
    if (entry.outputModalities.includes('IMAGE')) {
        throw new Error(`${path} не поддерживает IMAGE output modality`)
    }
    const hasVision = entry.capabilities.includes('VISION')
    const hasImageInput = entry.inputModalities.includes('IMAGE')
    if (hasVision !== hasImageInput) {
        throw new Error(`${path} VISION и IMAGE input должны объявляться совместно`)
    }
    if (entry.retentionStatus === 'ZERO_DATA_RETENTION'
        && entry.retentionDays !== null
        && entry.retentionDays !== 0) {
        throw new Error(`${path} ZERO_DATA_RETENTION требует retentionDays=0 или null`)
    }

    const noExtraPricing = entry.extraPricingJson === '{}'
    if (entry.pricingStatus === 'UNPRICED') {
        if (entry.pricingComplete
            || entry.inputUsdPer1mTokens !== null
            || entry.cachedInputUsdPer1mTokens !== null
            || entry.cacheWriteInputUsdPer1mTokens !== null
            || entry.outputUsdPer1mTokens !== null
            || !noExtraPricing) {
            throw new Error(`${path} содержит некорректный UNPRICED pricing`)
        }
    } else if (entry.pricingStatus === 'FREE') {
        const zero = (value: string | null) => value === null || /^0+(?:\.0+)?$/.test(value)
        if (!entry.pricingComplete
            || entry.inputUsdPer1mTokens === null
            || !zero(entry.inputUsdPer1mTokens)
            || entry.outputUsdPer1mTokens === null
            || !zero(entry.outputUsdPer1mTokens)
            || !zero(entry.cachedInputUsdPer1mTokens)
            || !zero(entry.cacheWriteInputUsdPer1mTokens)
            || !noExtraPricing) {
            throw new Error(`${path} содержит некорректный FREE pricing`)
        }
    } else if (entry.pricingStatus === 'CONFIGURED') {
        if (!entry.pricingComplete
            || entry.inputUsdPer1mTokens === null
            || entry.outputUsdPer1mTokens === null
            || entry.pricingVersion === null
            || !noExtraPricing) {
            throw new Error(`${path} содержит некорректный CONFIGURED pricing`)
        }
    } else if (entry.pricingComplete) {
        throw new Error(`${path} INCOMPLETE не может быть pricingComplete=true`)
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

    const [integerPart, fractionPart = ''] = value.split('.')
    const significantIntegerDigits = integerPart.replace(/^0+/, '').length
    if (significantIntegerDigits > 18 || fractionPart.length > 12) {
        throw new Error(
            `${path} должен помещаться в NUMERIC(30,12)`,
        )
    }

    return value
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

