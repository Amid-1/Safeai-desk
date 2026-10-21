// frontend/src/api/chat/chatCoreParsers.ts
// Контракты сущности чата, Slice и доступных серверных лимитов.

import { contractError, expectInstant, expectRecord, expectString, expectUuid } from '../runtime'
import type { Chat, ChatDetails, ChatSliceResponse, ChatCapabilities } from './chatTypes'
import { parseChatMessage } from './chatMessageParser'
import { expectBoolean, expectNonNegativeInteger, expectPositiveInteger } from './chatValidation'

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
