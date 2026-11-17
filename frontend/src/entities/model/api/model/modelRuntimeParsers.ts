// frontend/src/entities/model/api/model/modelRuntimeParsers.ts
// Назначение: проверка runtime-модели и результатов probe.

import type { RuntimeModelStatus, RuntimeModelProbe } from './modelTypes'
import { RUNTIME_MODEL_PROBE_STATUSES, RUNTIME_ROUTING_MODES, RUNTIME_DATA_RETENTION_STATUSES, RUNTIME_HEALTH_STATUSES, RUNTIME_PRICING_STATUSES } from './modelEnums'
import { requireObject, requireString, requireNonBlankString, optionalNonBlankString, requireBoolean, requireEnum, requirePositiveInteger, requireInteger, optionalDecimalString, requireInstant } from './modelWire'

export function parseRuntimeModelStatus(
    value: unknown,
    path = 'runtimeModel',
): RuntimeModelStatus {
    const raw = requireObject(value, path)
    // Older servers expose no binding-specific proof. Absence never implies consent.
    const assurance = raw.dataAssurance == null ? undefined
        : requireObject(raw.dataAssurance, `${path}.dataAssurance`)

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
        ...(assurance ? {dataAssurance: {
            noTraining: requireBoolean(assurance.noTraining, `${path}.dataAssurance.noTraining`),
            zeroDataRetention: requireBoolean(assurance.zeroDataRetention, `${path}.dataAssurance.zeroDataRetention`),
            source: optionalNonBlankString(assurance.source, `${path}.dataAssurance.source`),
            verifiedAt: assurance.verifiedAt == null ? null : requireInstant(assurance.verifiedAt, `${path}.dataAssurance.verifiedAt`),
            expiresAt: assurance.expiresAt == null ? null : requireInstant(assurance.expiresAt, `${path}.dataAssurance.expiresAt`),
        }} : {}),
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
