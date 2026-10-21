// frontend/src/api/chat/chatMessageParser.ts
// Разбор сообщений, токенов и финансовых статусов без подмены UNKNOWN нулём.

import {
    expectEnum, expectInstant, expectNullableEnum, expectNullableInstant,
    expectNullableNonNegativeInteger, expectNullableString, expectNullableUuid,
    expectRecord, expectString, expectUuid, parseDecimalString,
} from '../runtime'
import type { ChatMessageRole, PricingStatus, UsageStatus } from '../types'
import type { ChatMessage } from './chatTypes'
import { CHAT_ROLES, CHAT_STATUSES, AI_RESPONSE_STATUSES, USAGE_STATUSES, PRICING_STATUSES } from './chatEnums'

export function parseChatMessage(
    value: unknown,
    field = 'chatMessage',
): ChatMessage {
    const record = expectRecord(value, field)
    const role = expectEnum(
        record.role,
        `${field}.role`,
        CHAT_ROLES,
    )

    const inputTokens =
        expectNullableNonNegativeInteger(
            record.inputTokens ?? null,
            `${field}.inputTokens`,
        )

    const outputTokens =
        expectNullableNonNegativeInteger(
            record.outputTokens ?? null,
            `${field}.outputTokens`,
        )

    const costUsd = parseDecimalString(
        record.costUsd ?? null,
        `${field}.costUsd`,
    )

    return {
        id: expectUuid(
            record.id,
            `${field}.id`,
        ),
        clientRequestId: expectNullableUuid(
            record.clientRequestId ?? null,
            `${field}.clientRequestId`,
        ),
        replyToMessageId: expectNullableUuid(
            record.replyToMessageId ?? null,
            `${field}.replyToMessageId`,
        ),
        role,
        content: expectString(
            record.content,
            `${field}.content`,
            {
                allowEmpty: role === 'ASSISTANT',
                maxLength: 100_000,
            },
        ),
        status: expectEnum(
            record.status,
            `${field}.status`,
            CHAT_STATUSES,
        ),
        requestedModel: expectNullableString(
            record.requestedModel ?? null,
            `${field}.requestedModel`,
            {
                maxLength: 100,
            },
        ),
        model: expectNullableString(
            record.model ?? null,
            `${field}.model`,
            {
                maxLength: 100,
            },
        ),
        providerMessageId: expectNullableString(
            record.providerMessageId ?? null,
            `${field}.providerMessageId`,
            {
                maxLength: 255,
            },
        ),
        providerRequestId: expectNullableString(
            record.providerRequestId ?? null,
            `${field}.providerRequestId`,
            {
                maxLength: 255,
            },
        ),
        aiResponseStatus: expectNullableEnum(
            record.aiResponseStatus ?? null,
            `${field}.aiResponseStatus`,
            AI_RESPONSE_STATUSES,
        ),
        finishReason: expectNullableString(
            record.finishReason ?? null,
            `${field}.finishReason`,
            {
                maxLength: 100,
            },
        ),
        inputTokens,
        outputTokens,
        usageStatus: parseUsageStatus(
            record.usageStatus,
            role,
            inputTokens,
            outputTokens,
            field,
        ),
        costUsd,
        pricingStatus: parsePricingStatus(
            record.pricingStatus,
            role,
            costUsd,
            field,
        ),
        currency: expectNullableString(
            record.currency ?? null,
            `${field}.currency`,
            {
                maxLength: 3,
            },
        ),
        pricingVersion: expectNullableString(
            record.pricingVersion ?? null,
            `${field}.pricingVersion`,
            {
                maxLength: 64,
            },
        ),
        pricingCalculatedAt:
            expectNullableInstant(
                record.pricingCalculatedAt
                    ?? null,
                `${field}.pricingCalculatedAt`,
            ),
        createdAt: expectInstant(
            record.createdAt,
            `${field}.createdAt`,
        ),
    }
}

function parseUsageStatus(
    value: unknown,
    role: ChatMessageRole,
    inputTokens: number | null,
    outputTokens: number | null,
    field: string,
): UsageStatus {
    if (value !== undefined && value !== null) {
        return expectEnum(
            value,
            `${field}.usageStatus`,
            USAGE_STATUSES,
        )
    }

    // Совместимость с DTO без полей статуса при поэтапном обновлении сервера.
    if (role !== 'ASSISTANT') {
        return 'NOT_APPLICABLE'
    }

    if (
        inputTokens === null
        && outputTokens === null
    ) {
        return 'MISSING'
    }

    if (
        inputTokens === null
        || outputTokens === null
    ) {
        return 'PARTIAL'
    }

    return 'AVAILABLE'
}

function parsePricingStatus(
    value: unknown,
    role: ChatMessageRole,
    costUsd: string | null,
    field: string,
): PricingStatus {
    if (value !== undefined && value !== null) {
        return expectEnum(
            value,
            `${field}.pricingStatus`,
            PRICING_STATUSES,
        )
    }

    // Совместимость с DTO без полей статуса при поэтапном обновлении сервера.
    if (role !== 'ASSISTANT') {
        return 'NOT_APPLICABLE'
    }

    if (costUsd === null) {
        return 'UNPRICED'
    }

    // Точная проверка decimal-строки: Number() может превратить малую стоимость в ноль.
    return /^-?0+(?:\.0+)?$/.test(costUsd)
        ? 'FREE'
        : 'PRICED'
}
