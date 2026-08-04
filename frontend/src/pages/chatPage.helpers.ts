import type {
    Chat,
    ChatDetails,
    ChatMessage,
} from '../api/chatApi'

export type PendingTurnStatus =
    | 'SENDING'
    | 'PROCESSING'
    | 'SEND_UNKNOWN'
    | 'FAILED'
    | 'AMBIGUOUS'
    | 'RATE_LIMITED'

export type PendingTurn = {
    chatId: string
    clientRequestId: string
    optimisticMessageId: string
    content: string
    status: PendingTurnStatus
    error: string | null
    retryAfterUntil: number | null
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

    return [...byId.values()]
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
    switch (message.usageStatus) {
        case 'NOT_APPLICABLE':
            return 'usage: —'

        case 'MISSING':
            return 'usage: данные отсутствуют'

        case 'AVAILABLE':
            return (
                `usage: вход ${message.inputTokens ?? '—'}, `
                + `выход ${message.outputTokens ?? '—'}`
            )

        case 'PARTIAL':
            return (
                `usage: неполные данные — вход `
                + `${message.inputTokens ?? '—'}, `
                + `выход ${message.outputTokens ?? '—'}`
            )
    }
}

export function formatPricing(
    message: ChatMessage,
): string {
    switch (message.pricingStatus) {
        case 'NOT_APPLICABLE':
            return 'стоимость: —'

        case 'FREE':
            return `стоимость: ${formatMoney(
                '0',
                message.currency,
            )} — подтверждённо бесплатно`

        case 'PRICED':
            return `стоимость: ${formatMoney(
                message.costUsd,
                message.currency,
            )}`

        case 'UNPRICED':
            return 'стоимость: не рассчитана'

        case 'CALCULATION_FAILED':
            return 'стоимость: ошибка расчёта'
    }
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
