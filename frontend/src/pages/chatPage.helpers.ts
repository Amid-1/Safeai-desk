// frontend/src/pages/chatPage.helpers.ts
import type {
    Chat,
    ChatDetails,
    ChatMessage,
    KnowledgeMode,
} from '../api/chatApi'

export type PendingTurnStatus =
    | 'SENDING'
    | 'PROCESSING'
    | 'SEND_UNKNOWN'
    | 'FAILED'
    | 'AMBIGUOUS'
    | 'RATE_LIMITED'
    | 'QUOTA_BLOCKED'
    | 'ACCESS_REVOKED'
    | 'IDEMPOTENCY_CONFLICT'

export type PendingTurn = {
    chatId: string
    clientRequestId: string
    optimisticMessageId: string
    content: string
    status: PendingTurnStatus
    error: string | null
    retryAfterUntil: number | null
    knowledgeBaseId: string | null
    knowledgeMode: KnowledgeMode
}

export type DisplayMessage = ChatMessage & {
    uiStatus?: PendingTurnStatus
}

export function normalizeMessageContent(
    value: string,
): string {
    return value
        .replace(/\r\n/g, '\n')
        .replace(/\r/g, '\n')
}

export function hasMeaningfulContent(
    value: string,
): boolean {
    return value.trim().length > 0
}

export function createPendingTurn(
    chatId: string,
    content: string,
    clientRequestId: string,
    knowledgeBaseId: string | null = null,
    knowledgeMode: KnowledgeMode = 'GENERAL',
): PendingTurn {
    return {
        chatId,
        clientRequestId,
        optimisticMessageId:
            `pending-${clientRequestId}`,
        content,
        status: 'SENDING',
        error: null,
        retryAfterUntil: null,
        knowledgeBaseId,
        knowledgeMode,
    }
}

export function createOptimisticMessage(
    pendingTurn: PendingTurn,
): DisplayMessage {
    return {
        id: pendingTurn.optimisticMessageId,
        clientRequestId:
            pendingTurn.clientRequestId,
        replyToMessageId: null,
        role: 'USER',
        content: pendingTurn.content,
        status: 'COMPLETED',
        requestedModel: null,
        model: null,
        providerMessageId: null,
        providerRequestId: null,
        aiResponseStatus: null,
        finishReason: null,
        inputTokens: null,
        outputTokens: null,
        usageStatus: 'NOT_APPLICABLE',
        costUsd: null,
        pricingStatus: 'NOT_APPLICABLE',
        currency: null,
        pricingVersion: null,
        pricingCalculatedAt: null,
        createdAt: new Date().toISOString(),
        uiStatus: pendingTurn.status,
    }
}

export function buildDisplayMessages(
    messages: ChatMessage[],
    pendingTurn?: PendingTurn,
): DisplayMessage[] {
    if (!pendingTurn) {
        return messages
    }

    const serverUserExists = messages.some(
        (message) =>
            message.role === 'USER'
            && message.clientRequestId
                === pendingTurn.clientRequestId,
    )

    if (serverUserExists) {
        return messages.map((message) => {
            if (
                message.role === 'USER'
                && message.clientRequestId
                    === pendingTurn.clientRequestId
            ) {
                return {
                    ...message,
                    uiStatus:
                        pendingTurn.status,
                }
            }

            return message
        })
    }

    return mergeMessages(
        messages,
        [
            createOptimisticMessage(
                pendingTurn,
            ),
        ],
    )
}

export function mergeChatDetails(
    current: ChatDetails,
    incoming: ChatDetails,
): ChatDetails {
    return {
        ...incoming,
        messages: mergeMessages(
            current.messages,
            incoming.messages,
        ),
    }
}

export function mergeMessages(
    first: ChatMessage[],
    second: ChatMessage[],
): ChatMessage[] {
    const byId = new Map<
        string,
        ChatMessage
    >()

    const optimisticByRequestId =
        new Map<string, string>()

    for (const message of [
        ...first,
        ...second,
    ]) {
        if (
            message.id.startsWith('pending-')
            && message.clientRequestId
        ) {
            optimisticByRequestId.set(
                message.clientRequestId,
                message.id,
            )
        }

        byId.set(message.id, message)
    }

    for (const message of byId.values()) {
        if (
            !message.id.startsWith('pending-')
            && message.clientRequestId
        ) {
            const optimisticId =
                optimisticByRequestId.get(
                    message.clientRequestId,
                )

            if (optimisticId) {
                byId.delete(optimisticId)
            }
        }
    }

    return [...byId.values()].sort(
        (firstMessage, secondMessage) => {
            const timeComparison =
                firstMessage.createdAt.localeCompare(
                    secondMessage.createdAt,
                )

            if (timeComparison !== 0) {
                return timeComparison
            }

            return firstMessage.id.localeCompare(
                secondMessage.id,
            )
        },
    )
}

export function mergeChats(
    current: Chat[],
    incoming: Chat[],
): Chat[] {
    const byId = new Map(
        current.map(
            (chat) => [chat.id, chat],
        ),
    )

    incoming.forEach((chat) => {
        byId.set(chat.id, chat)
    })

    return [...byId.values()].sort(
        (first, second) => {
            const time =
                second.updatedAt.localeCompare(
                    first.updatedAt,
                )

            return time !== 0
                ? time
                : second.id.localeCompare(
                    first.id,
                )
        },
    )
}

export function moveChatToTop(
    current: Chat[],
    details: ChatDetails,
): Chat[] {
    const updated: Chat = {
        id: details.id,
        title: details.title,
        createdAt: details.createdAt,
        updatedAt: details.updatedAt,
    }

    return [
        updated,
        ...current.filter(
            (chat) => chat.id !== updated.id,
        ),
    ]
}

export function formatUsage(
    message: ChatMessage,
): string {
    return `Токены: ${formatUsageValue(message)}`
}

export function formatUsageValue(
    message: ChatMessage,
): string {
    switch (message.usageStatus) {
        case 'NOT_APPLICABLE':
            return 'не применимо'

        case 'MISSING':
            return 'данные провайдера отсутствуют'

        case 'AVAILABLE':
            return (
                `${message.inputTokens ?? '—'} вход · `
                + `${message.outputTokens ?? '—'} выход`
            )

        case 'PARTIAL':
            return (
                'неполные данные · '
                + `${message.inputTokens ?? '—'} вход · `
                + `${message.outputTokens ?? '—'} выход`
            )
    }
}

export function formatPricing(
    message: ChatMessage,
): string {
    return `Стоимость: ${formatPricingValue(message)}`
}

export function formatPricingValue(
    message: ChatMessage,
): string {
    switch (message.pricingStatus) {
        case 'NOT_APPLICABLE':
            return 'не применимо'

        case 'FREE':
            return `${formatMoney(
                '0',
                message.currency,
            )} · ${isMockModel(message.model)
                ? 'демо-модель'
                : 'модель не тарифицируется'}`

        case 'PRICED':
            return formatMoney(
                message.costUsd,
                message.currency,
            )

        case 'UNPRICED':
            return 'ещё не рассчитана'

        case 'CALCULATION_FAILED':
            return 'ошибка расчёта'
    }
}

export function isMockModel(
    model: string | null,
): boolean {
    return model?.toLowerCase()
        === 'mock-safeai'
}

export function getModelDisplayName(
    model: string | null,
): string {
    if (!model) {
        return 'Модель не указана'
    }

    if (isMockModel(model)) {
        return 'Демонстрационная модель SafeAI'
    }

    return model
}

export function getVisibleMessageContent(
    message: ChatMessage,
): string {
    const mockPrefix =
        'Mock AI provider response:'

    if (
        message.role === 'ASSISTANT'
        && isMockModel(message.model)
        && message.content.startsWith(
            mockPrefix,
        )
    ) {
        return message.content
            .slice(mockPrefix.length)
            .trimStart()
    }

    return message.content
}

export function getAiResponseLabel(
    message: ChatMessage,
): string | null {
    switch (message.aiResponseStatus) {
        case 'REFUSED':
            return 'Запрос отклонён моделью'

        case 'INCOMPLETE':
            return 'Ответ завершён не полностью'

        case 'COMPLETED':
        case null:
            return null
    }
}

export function isSafeToPrepareNewRequest(
    status: PendingTurnStatus,
): boolean {
    return status === 'FAILED'
        || status === 'QUOTA_BLOCKED'
        || status === 'RATE_LIMITED'
}

export function isProcessingPendingStatus(
    status: PendingTurnStatus,
): boolean {
    return status === 'SENDING'
        || status === 'PROCESSING'
        || status === 'SEND_UNKNOWN'
}

function formatMoney(
    value: string | null,
    currency: string | null,
): string {
    if (value === null) {
        return '—'
    }

    const code = currency ?? 'USD'
    const numeric = Number(value)

    if (!Number.isFinite(numeric)) {
        return `${value} ${code}`
    }

    return new Intl.NumberFormat(
        'ru-RU',
        {
            style: 'currency',
            currency: code,
            minimumFractionDigits: 2,
            maximumFractionDigits: 12,
        },
    ).format(numeric)
}
