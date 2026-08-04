import {
    API_TIMEOUTS,
    apiRequest,
} from './http'
import {
    buildQueryString,
    normalizePage,
    normalizePageSize,
    uuidPathSegment,
} from './query'
import type {
    AiResponseStatus,
    ChatMessageRole,
    ChatMessageStatus,
    PricingStatus,
    UsageStatus,
} from './types'
import {
    contractError,
    expectEnum,
    expectInstant,
    expectNullableEnum,
    expectNullableInstant,
    expectNullableNonNegativeInteger,
    expectNullableString,
    expectNullableUuid,
    expectRecord,
    expectString,
    expectUuid,
    parseDecimalString,
    parsePageResponse,
} from './runtime'
import type { PageResponse } from '../utils/page'

const CHAT_ROLES: readonly ChatMessageRole[] = [
    'USER',
    'ASSISTANT',
    'SYSTEM',
]

const CHAT_STATUSES: readonly ChatMessageStatus[] = [
    'PENDING',
    'COMPLETED',
    'FAILED',
]

const AI_RESPONSE_STATUSES: readonly AiResponseStatus[] = [
    'COMPLETED',
    'REFUSED',
    'INCOMPLETE',
]

const USAGE_STATUSES: readonly UsageStatus[] = [
    'NOT_APPLICABLE',
    'AVAILABLE',
    'MISSING',
    'PARTIAL',
]

const PRICING_STATUSES: readonly PricingStatus[] = [
    'NOT_APPLICABLE',
    'PRICED',
    'FREE',
    'UNPRICED',
    'CALCULATION_FAILED',
]

const TURN_STATES = [
    'PROCESSING',
    'SUCCEEDED',
    'FAILED',
    'AMBIGUOUS',
] as const

export type ChatTurnState =
    typeof TURN_STATES[number]

export type Chat = {
    id: string
    title: string
    createdAt: string
    updatedAt: string
}

export type ChatMessage = {
    id: string
    clientRequestId: string | null
    replyToMessageId: string | null

    role: ChatMessageRole
    content: string
    status: ChatMessageStatus

    requestedModel: string | null
    model: string | null
    providerMessageId: string | null
    providerRequestId: string | null

    aiResponseStatus: AiResponseStatus | null
    finishReason: string | null

    inputTokens: number | null
    outputTokens: number | null
    usageStatus: UsageStatus

    costUsd: string | null
    pricingStatus: PricingStatus
    currency: string | null
    pricingVersion: string | null
    pricingCalculatedAt: string | null

    createdAt: string
}

export type ChatDetails = Chat & {
    messages: ChatMessage[]
}

export type SendMessageRequest = {
    content: string
    clientRequestId: string
}

export type ChatTurnStatus = {
    chatId: string
    clientRequestId: string
    state: ChatTurnState
    userMessageId: string | null
    assistantMessageId: string | null
    errorCode: string | null
    errorMessage: string | null
    createdAt: string
    updatedAt: string
}

type RequestOptions = {
    signal?: AbortSignal
}

export async function getChats(
    page = 0,
    size = 50,
    options: RequestOptions = {},
): Promise<PageResponse<Chat>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(
            size,
            50,
            200,
        ),
    })

    const response = await apiRequest<unknown>(
        `/api/chats${query}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parsePageResponse(
        response,
        parseChat,
    )
}

export async function getChatById(
    chatId: string,
    options: RequestOptions = {},
): Promise<ChatDetails> {
    const response = await apiRequest<unknown>(
        `/api/chats/${uuidPathSegment(chatId)}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseChatDetails(response)
}

export async function getChatMessages(
    chatId: string,
    page = 0,
    size = 50,
    options: RequestOptions = {},
): Promise<PageResponse<ChatMessage>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(
            size,
            50,
            100,
        ),
    })

    const response = await apiRequest<unknown>(
        `/api/chats/${uuidPathSegment(chatId)}/messages${query}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parsePageResponse(
        response,
        parseChatMessage,
    )
}

export async function createChat(
    title: string,
    options: RequestOptions = {},
): Promise<Chat> {
    const response = await apiRequest<unknown>(
        '/api/chats',
        {
            method: 'POST',
            json: {
                title,
            },
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseChat(response)
}

export async function sendMessage(
    chatId: string,
    request: SendMessageRequest,
    options: RequestOptions = {},
): Promise<ChatDetails> {
    const response = await apiRequest<unknown>(
        `/api/chats/${uuidPathSegment(chatId)}/messages`,
        {
            method: 'POST',
            json: request,
            signal: options.signal,

            // Должен быть немного больше внешнего proxy timeout 90s,
            // чтобы frontend получил серверный 504/503, а не оборвал
            // ожидание раньше reverse proxy.
            timeoutMs: API_TIMEOUTS.chat,
        },
    )

    return parseChatDetails(response)
}

export async function getChatTurnStatus(
    chatId: string,
    clientRequestId: string,
    options: RequestOptions = {},
): Promise<ChatTurnStatus> {
    const response = await apiRequest<unknown>(
        `/api/chats/${uuidPathSegment(chatId)}/turns/by-client-request/${uuidPathSegment(clientRequestId)}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseChatTurnStatus(response)
}

export function parseChat(
    value: unknown,
    field = 'chat',
): Chat {
    const record = expectRecord(value, field)

    return {
        id: expectUuid(
            record.id,
            `${field}.id`,
        ),
        title: expectString(
            record.title,
            `${field}.title`,
            {
                maxLength: 255,
            },
        ),
        createdAt: expectInstant(
            record.createdAt,
            `${field}.createdAt`,
        ),
        updatedAt: expectInstant(
            record.updatedAt,
            `${field}.updatedAt`,
        ),
    }
}

export function parseChatDetails(
    value: unknown,
    field = 'chatDetails',
): ChatDetails {
    const record = expectRecord(value, field)
    const chat = parseChat(record, field)

    if (!Array.isArray(record.messages)) {
        throw contractError(
            `${field}.messages должен быть массивом`,
        )
    }

    return {
        ...chat,
        messages: record.messages.map(
            (message, index) =>
                parseChatMessage(
                    message,
                    `${field}.messages[${index}]`,
                ),
        ),
    }
}

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

export function parseChatTurnStatus(
    value: unknown,
    field = 'chatTurnStatus',
): ChatTurnStatus {
    const record = expectRecord(value, field)

    return {
        chatId: expectUuid(
            record.chatId,
            `${field}.chatId`,
        ),
        clientRequestId: expectUuid(
            record.clientRequestId,
            `${field}.clientRequestId`,
        ),
        state: expectEnum(
            record.state,
            `${field}.state`,
            TURN_STATES,
        ),
        userMessageId: expectNullableUuid(
            record.userMessageId ?? null,
            `${field}.userMessageId`,
        ),
        assistantMessageId:
            expectNullableUuid(
                record.assistantMessageId
                    ?? null,
                `${field}.assistantMessageId`,
            ),
        errorCode: expectNullableString(
            record.errorCode ?? null,
            `${field}.errorCode`,
            {
                maxLength: 100,
            },
        ),
        errorMessage: expectNullableString(
            record.errorMessage ?? null,
            `${field}.errorMessage`,
            {
                maxLength: 1_000,
            },
        ),
        createdAt: expectInstant(
            record.createdAt,
            `${field}.createdAt`,
        ),
        updatedAt: expectInstant(
            record.updatedAt,
            `${field}.updatedAt`,
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

    // Обратная совместимость со старым DTO.
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

    // Обратная совместимость со старым DTO.
    if (role !== 'ASSISTANT') {
        return 'NOT_APPLICABLE'
    }

    if (costUsd === null) {
        return 'UNPRICED'
    }

    return Number(costUsd) === 0
        ? 'FREE'
        : 'PRICED'
}
