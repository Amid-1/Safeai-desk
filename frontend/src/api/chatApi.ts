// ============================================================
// frontend/src/api/chatApi.ts
// ============================================================
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
} from './runtime'

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
    'NEW',
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

export type ChatSliceResponse<T> = {
    content: T[]
    page: number
    size: number
    first: boolean
    last: boolean
    hasNext: boolean
    hasPrevious: boolean
}

export type ChatCapabilities = {
    maxMessageChars: number
    maxChatPageSize: number
    maxMessagePageSize: number
    detailsMessageLimit: number
}

export type KnowledgeMode =
    | 'GENERAL'
    | 'KNOWLEDGE_ASSISTED'
    | 'KNOWLEDGE_ONLY'

export type AnswerCitation = {
    label: string
    chunkId: string
    documentId: string
    documentVersionId: string
    documentName: string
    versionNumber: number
    chunkOrdinal: number
    pageFrom: number | null
    pageTo: number | null
    heading: string | null
    contentSha256: string
}

export type AnswerPassport = {
    id: string
    chatTurnId: string
    retrievalRunId: string
    knowledgeBaseId: string
    knowledgeMode: Exclude<KnowledgeMode, 'GENERAL'>
    provider: string
    requestedModel: string
    resolvedModel: string
    embeddingModel: string
    contextSha256: string
    answerSha256: string
    evidenceSufficient: boolean
    citationsValid: boolean
    createdAt: string
    citations: AnswerCitation[]
}

export type SendMessageRequest = {
    content: string
    clientRequestId: string
    knowledgeBaseId?: string | null
    knowledgeMode?: KnowledgeMode
}

export type SendMessageResponse = {
    chatId: string
    turnId: string
    clientRequestId: string
    providerOperationId: string
    state: 'SUCCEEDED'
    replay: boolean
    userMessage: ChatMessage
    assistantMessage: ChatMessage
    chatUpdatedAt: string | null
    createdAt: string
    completedAt: string
    answerPassport: AnswerPassport | null
}

export type ChatTurnStatus = {
    chatId: string
    turnId: string
    clientRequestId: string
    providerOperationId: string
    state: ChatTurnState
    provider: string | null
    requestedModel: string | null
    resolvedModel: string | null
    providerRequestId: string | null
    providerErrorType: string | null
    failureCode: string | null
    outcomeAmbiguous: boolean
    leaseUntil: string | null
    providerCallStartedAt: string | null
    createdAt: string
    updatedAt: string
    completedAt: string | null
    userMessage: ChatMessage | null
    assistantMessage: ChatMessage | null
}

type RequestOptions = {
    signal?: AbortSignal
}

export async function getChatCapabilities(
    options: RequestOptions = {},
): Promise<ChatCapabilities> {
    const response = await apiRequest<unknown>(
        '/api/chats/capabilities',
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseChatCapabilities(response)
}

export async function getChats(
    page = 0,
    size = 50,
    options: RequestOptions = {},
): Promise<ChatSliceResponse<Chat>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(
            size,
            50,
            100,
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

    return parseChatSliceResponse(
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
): Promise<ChatSliceResponse<ChatMessage>> {
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

    return parseChatSliceResponse(
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

export async function archiveChat(
    chatId: string,
    options: RequestOptions = {},
): Promise<void> {
    await apiRequest<void>(
        `/api/chats/${uuidPathSegment(chatId)}`,
        {
            method: 'DELETE',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )
}

export async function sendMessage(
    chatId: string,
    request: SendMessageRequest,
    options: RequestOptions = {},
): Promise<SendMessageResponse> {
    const response = await apiRequest<unknown>(
        `/api/chats/${uuidPathSegment(chatId)}/messages`,
        {
            method: 'POST',
            json: request,
            signal: options.signal,

            // Немного больше reverse-proxy timeout 90s: клиент должен
            // получить контролируемый ответ proxy/backend, а не оборвать
            // соединение раньше него.
            timeoutMs: API_TIMEOUTS.chat,
        },
    )

    return parseSendMessageResponse(response)
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

/**
 * Retrieves the immutable citation record independently of a live send.
 * This is required when a page is restored after the original
 * SendMessageResponse has left browser memory.
 */
export async function getAnswerPassport(
    chatId: string,
    turnId: string,
    options: RequestOptions = {},
): Promise<AnswerPassport> {
    const response = await apiRequest<unknown>(
        `/api/chats/${uuidPathSegment(chatId)}/turns/${uuidPathSegment(turnId)}/answer-passport`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseAnswerPassport(response)
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

export function parseSendMessageResponse(
    value: unknown,
    field = 'sendMessageResponse',
): SendMessageResponse {
    const record = expectRecord(value, field)
    const state = expectString(
        record.state,
        `${field}.state`,
        { maxLength: 32 },
    )

    if (state !== 'SUCCEEDED') {
        throw contractError(
            `${field}.state должен быть SUCCEEDED для успешного HTTP-ответа`,
        )
    }

    return {
        chatId: expectUuid(
            record.chatId,
            `${field}.chatId`,
        ),
        turnId: expectUuid(
            record.turnId,
            `${field}.turnId`,
        ),
        clientRequestId: expectUuid(
            record.clientRequestId,
            `${field}.clientRequestId`,
        ),
        providerOperationId: expectUuid(
            record.providerOperationId,
            `${field}.providerOperationId`,
        ),
        state,
        replay: expectBoolean(
            record.replay,
            `${field}.replay`,
        ),
        userMessage: parseChatMessage(
            record.userMessage,
            `${field}.userMessage`,
        ),
        assistantMessage: parseChatMessage(
            record.assistantMessage,
            `${field}.assistantMessage`,
        ),
        chatUpdatedAt: expectNullableInstant(
            record.chatUpdatedAt ?? null,
            `${field}.chatUpdatedAt`,
        ),
        createdAt: expectInstant(
            record.createdAt,
            `${field}.createdAt`,
        ),
        completedAt: expectInstant(
            record.completedAt,
            `${field}.completedAt`,
        ),
        answerPassport: record.answerPassport == null
            ? null
            : parseAnswerPassport(
                record.answerPassport,
                `${field}.answerPassport`,
            ),
    }
}

export function parseAnswerPassport(
    value: unknown,
    field = 'answerPassport',
): AnswerPassport {
    const record = expectRecord(value, field)
    const mode = expectEnum(
        record.knowledgeMode,
        `${field}.knowledgeMode`,
        ['KNOWLEDGE_ASSISTED', 'KNOWLEDGE_ONLY'] as const,
    )
    if (!Array.isArray(record.citations)) {
        throw contractError(`${field}.citations должен быть массивом`)
    }
    return {
        id: expectUuid(record.id, `${field}.id`),
        chatTurnId: expectUuid(record.chatTurnId, `${field}.chatTurnId`),
        retrievalRunId: expectUuid(
            record.retrievalRunId,
            `${field}.retrievalRunId`,
        ),
        knowledgeBaseId: expectUuid(
            record.knowledgeBaseId,
            `${field}.knowledgeBaseId`,
        ),
        knowledgeMode: mode,
        provider: expectString(record.provider, `${field}.provider`),
        requestedModel: expectString(
            record.requestedModel,
            `${field}.requestedModel`,
        ),
        resolvedModel: expectString(
            record.resolvedModel,
            `${field}.resolvedModel`,
        ),
        embeddingModel: expectString(
            record.embeddingModel,
            `${field}.embeddingModel`,
        ),
        contextSha256: expectString(
            record.contextSha256,
            `${field}.contextSha256`,
            { maxLength: 64 },
        ),
        answerSha256: expectString(
            record.answerSha256,
            `${field}.answerSha256`,
            { maxLength: 64 },
        ),
        evidenceSufficient: expectBoolean(
            record.evidenceSufficient,
            `${field}.evidenceSufficient`,
        ),
        citationsValid: expectBoolean(
            record.citationsValid,
            `${field}.citationsValid`,
        ),
        createdAt: expectInstant(record.createdAt, `${field}.createdAt`),
        citations: record.citations.map((citation, index) => {
            const item = expectRecord(
                citation,
                `${field}.citations[${index}]`,
            )
            return {
                label: expectString(item.label, `${field}.citations[${index}].label`),
                chunkId: expectUuid(item.chunkId, `${field}.citations[${index}].chunkId`),
                documentId: expectUuid(item.documentId, `${field}.citations[${index}].documentId`),
                documentVersionId: expectUuid(item.documentVersionId, `${field}.citations[${index}].documentVersionId`),
                documentName: expectString(item.documentName, `${field}.citations[${index}].documentName`),
                versionNumber: expectNullableNonNegativeInteger(item.versionNumber, `${field}.citations[${index}].versionNumber`) ?? 0,
                chunkOrdinal: expectNullableNonNegativeInteger(item.chunkOrdinal, `${field}.citations[${index}].chunkOrdinal`) ?? 0,
                pageFrom: expectNullableNonNegativeInteger(item.pageFrom ?? null, `${field}.citations[${index}].pageFrom`),
                pageTo: expectNullableNonNegativeInteger(item.pageTo ?? null, `${field}.citations[${index}].pageTo`),
                heading: expectNullableString(item.heading ?? null, `${field}.citations[${index}].heading`),
                contentSha256: expectString(item.contentSha256, `${field}.citations[${index}].contentSha256`, { maxLength: 64 }),
            }
        }),
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
        turnId: expectUuid(
            record.turnId,
            `${field}.turnId`,
        ),
        clientRequestId: expectUuid(
            record.clientRequestId,
            `${field}.clientRequestId`,
        ),
        providerOperationId: expectUuid(
            record.providerOperationId,
            `${field}.providerOperationId`,
        ),
        state: expectEnum(
            record.state,
            `${field}.state`,
            TURN_STATES,
        ),
        provider: expectNullableString(
            record.provider ?? null,
            `${field}.provider`,
            { maxLength: 100 },
        ),
        requestedModel: expectNullableString(
            record.requestedModel ?? null,
            `${field}.requestedModel`,
            { maxLength: 100 },
        ),
        resolvedModel: expectNullableString(
            record.resolvedModel ?? null,
            `${field}.resolvedModel`,
            { maxLength: 100 },
        ),
        providerRequestId: expectNullableString(
            record.providerRequestId ?? null,
            `${field}.providerRequestId`,
            { maxLength: 255 },
        ),
        providerErrorType: expectNullableString(
            record.providerErrorType ?? null,
            `${field}.providerErrorType`,
            { maxLength: 100 },
        ),
        failureCode: expectNullableString(
            record.failureCode ?? null,
            `${field}.failureCode`,
            { maxLength: 100 },
        ),
        outcomeAmbiguous: expectBoolean(
            record.outcomeAmbiguous,
            `${field}.outcomeAmbiguous`,
        ),
        leaseUntil: expectNullableInstant(
            record.leaseUntil ?? null,
            `${field}.leaseUntil`,
        ),
        providerCallStartedAt: expectNullableInstant(
            record.providerCallStartedAt ?? null,
            `${field}.providerCallStartedAt`,
        ),
        createdAt: expectInstant(
            record.createdAt,
            `${field}.createdAt`,
        ),
        updatedAt: expectInstant(
            record.updatedAt,
            `${field}.updatedAt`,
        ),
        completedAt: expectNullableInstant(
            record.completedAt ?? null,
            `${field}.completedAt`,
        ),
        userMessage: record.userMessage == null
            ? null
            : parseChatMessage(
                record.userMessage,
                `${field}.userMessage`,
            ),
        assistantMessage: record.assistantMessage == null
            ? null
            : parseChatMessage(
                record.assistantMessage,
                `${field}.assistantMessage`,
            ),
    }
}

export function parseChatSliceResponse<T>(
    value: unknown,
    itemParser: (
        value: unknown,
        field: string,
    ) => T,
    field = 'chatSlice',
): ChatSliceResponse<T> {
    const record = expectRecord(value, field)

    if (!Array.isArray(record.content)) {
        throw contractError(
            `${field}.content должен быть массивом`,
        )
    }

    const page = expectNonNegativeInteger(
        record.page,
        `${field}.page`,
    )
    const size = expectPositiveInteger(
        record.size,
        `${field}.size`,
    )
    const first = expectBoolean(
        record.first,
        `${field}.first`,
    )
    const last = expectBoolean(
        record.last,
        `${field}.last`,
    )
    const hasNext = expectBoolean(
        record.hasNext,
        `${field}.hasNext`,
    )
    const hasPrevious = expectBoolean(
        record.hasPrevious,
        `${field}.hasPrevious`,
    )

    if (first !== (page === 0)) {
        throw contractError(
            `${field}.first не согласован с page`,
        )
    }

    if (last === hasNext) {
        throw contractError(
            `${field}.last не согласован с hasNext`,
        )
    }

    if (hasPrevious !== (page > 0)) {
        throw contractError(
            `${field}.hasPrevious не согласован с page`,
        )
    }

    return {
        content: record.content.map(
            (item, index) =>
                itemParser(
                    item,
                    `${field}.content[${index}]`,
                ),
        ),
        page,
        size,
        first,
        last,
        hasNext,
        hasPrevious,
    }
}

export function parseChatCapabilities(
    value: unknown,
    field = 'chatCapabilities',
): ChatCapabilities {
    const record = expectRecord(value, field)

    return {
        maxMessageChars: expectPositiveInteger(
            record.maxMessageChars,
            `${field}.maxMessageChars`,
        ),
        maxChatPageSize: expectPositiveInteger(
            record.maxChatPageSize,
            `${field}.maxChatPageSize`,
        ),
        maxMessagePageSize: expectPositiveInteger(
            record.maxMessagePageSize,
            `${field}.maxMessagePageSize`,
        ),
        detailsMessageLimit: expectPositiveInteger(
            record.detailsMessageLimit,
            `${field}.detailsMessageLimit`,
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

    // Rolling-deployment compatibility with the pre-status DTO.
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

    // Rolling-deployment compatibility with the pre-status DTO.
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

function expectBoolean(
    value: unknown,
    field: string,
): boolean {
    if (typeof value !== 'boolean') {
        throw contractError(
            `${field} должен быть boolean`,
        )
    }

    return value
}

function expectNonNegativeInteger(
    value: unknown,
    field: string,
): number {
    if (
        typeof value !== 'number'
        || !Number.isSafeInteger(value)
        || value < 0
    ) {
        throw contractError(
            `${field} должен быть неотрицательным целым числом`,
        )
    }

    return value
}

function expectPositiveInteger(
    value: unknown,
    field: string,
): number {
    const parsed = expectNonNegativeInteger(
        value,
        field,
    )

    if (parsed < 1) {
        throw contractError(
            `${field} должен быть положительным`,
        )
    }

    return parsed
}
