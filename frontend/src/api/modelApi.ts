import {
    API_TIMEOUTS,
    apiRequest,
} from './http'
import {
    expectBoolean,
    expectEnum,
    expectNonNegativeInteger,
    expectRecord,
    expectString,
    parseDecimalString,
} from './runtime'

const ROUTING_MODES = ['SINGLE_PROVIDER_STATIC'] as const
const RETENTION_STATUSES = ['NOT_DECLARED'] as const
const HEALTH_STATUSES = ['NOT_PROBED'] as const
const PRICING_STATUSES = ['UNPRICED', 'FREE', 'CONFIGURED'] as const

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
    dataRetentionStatus: typeof RETENTION_STATUSES[number]
    healthStatus: typeof HEALTH_STATUSES[number]
    pricingStatus: typeof PRICING_STATUSES[number]
    inputUsdPer1mTokens: string | null
    outputUsdPer1mTokens: string | null
    pricingVersion: string | null
}

export async function getRuntimeModelStatus(
    signal?: AbortSignal,
): Promise<RuntimeModelStatus> {
    const value = await apiRequest<unknown>('/api/admin/models/runtime', {
        method: 'GET',
        signal,
        timeoutMs: API_TIMEOUTS.default,
    })
    const record = expectRecord(value, 'runtimeModel')

    return {
        provider: expectString(record.provider, 'runtimeModel.provider', {maxLength: 64}),
        model: expectString(record.model, 'runtimeModel.model', {maxLength: 100}),
        enabled: expectBoolean(record.enabled, 'runtimeModel.enabled'),
        routingMode: expectEnum(record.routingMode, 'runtimeModel.routingMode', ROUTING_MODES),
        maxInputTokens: expectNonNegativeInteger(record.maxInputTokens, 'runtimeModel.maxInputTokens'),
        maxOutputTokens: expectNonNegativeInteger(record.maxOutputTokens, 'runtimeModel.maxOutputTokens'),
        toolsSupported: expectBoolean(record.toolsSupported, 'runtimeModel.toolsSupported'),
        visionSupported: expectBoolean(record.visionSupported, 'runtimeModel.visionSupported'),
        structuredOutputSupported: expectBoolean(record.structuredOutputSupported, 'runtimeModel.structuredOutputSupported'),
        dataRetentionStatus: expectEnum(record.dataRetentionStatus, 'runtimeModel.dataRetentionStatus', RETENTION_STATUSES),
        healthStatus: expectEnum(record.healthStatus, 'runtimeModel.healthStatus', HEALTH_STATUSES),
        pricingStatus: expectEnum(record.pricingStatus, 'runtimeModel.pricingStatus', PRICING_STATUSES),
        inputUsdPer1mTokens: parseDecimalString(record.inputUsdPer1mTokens, 'runtimeModel.inputUsdPer1mTokens'),
        outputUsdPer1mTokens: parseDecimalString(record.outputUsdPer1mTokens, 'runtimeModel.outputUsdPer1mTokens'),
        pricingVersion: record.pricingVersion === null
            ? null
            : expectString(record.pricingVersion, 'runtimeModel.pricingVersion', {maxLength: 64}),
    }
}
