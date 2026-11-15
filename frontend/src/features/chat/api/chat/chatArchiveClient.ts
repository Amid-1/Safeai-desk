// frontend/src/features/chat/api/chat/chatArchiveClient.ts
// API operations for tenant-scoped chat lifecycle, separate from AI execution.
import { apiRequest } from '../../../../shared/api/http'
import { uuidPathSegment, buildQueryString, normalizePage, normalizePageSize } from '../../../../shared/api/query'
import { parseChatDetails, parseChatSliceResponse } from './chatCoreParsers'
import { parseChat } from './chatCoreParsers'
import type { Chat, ChatDetails, ChatSliceResponse } from './chatTypes'

export type ChatErasureCapability = {
    allowed: boolean
    reason: string
    version: number
}
export type ChatErasureOperation = {
    operationId: string
    chatId: string
    state: 'ERASING' | 'NEEDS_REVIEW' | 'BLOCKED_BY_RETENTION' | 'ERASED' | 'CANCELLED'
    createdAt: string
    completedAt: string | null
    lastErrorCode: string | null
}

export async function getArchivedChats(
    page = 0, size = 50, signal?: AbortSignal,
): Promise<ChatSliceResponse<Chat>> {
    const query = buildQueryString({ page: normalizePage(page), size: normalizePageSize(size, 50, 100) })
    return parseChatSliceResponse(await apiRequest<unknown>(`/api/chats/archived${query}`, {
        method: 'GET', signal,
    }), parseChat)
}

export async function getArchivedChatDetails(chatId: string, signal?: AbortSignal): Promise<ChatDetails> {
    return parseChatDetails(await apiRequest<unknown>(
        `/api/chats/${uuidPathSegment(chatId)}/archived`, { method: 'GET', signal },
    ))
}

export async function restoreChat(chatId: string): Promise<void> {
    await apiRequest<void>(`/api/chats/${uuidPathSegment(chatId)}/restore`, { method: 'POST' })
}

export async function getChatErasureCapability(
    chatId: string, signal?: AbortSignal,
): Promise<ChatErasureCapability> {
    const value = await apiRequest<ChatErasureCapability>(
        `/api/chats/${uuidPathSegment(chatId)}/erasure/capability`, { method: 'GET', signal },
    )
    if (!value || typeof value.allowed !== 'boolean' || typeof value.reason !== 'string'
        || !Number.isSafeInteger(value.version) || value.version < 0) {
        throw new Error('Недостоверный ответ проверки возможности удаления чата.')
    }
    return value
}

function parseOperation(value: ChatErasureOperation, chatId: string): ChatErasureOperation {
    if (!value || value.chatId !== chatId || !['ERASING', 'NEEDS_REVIEW',
        'BLOCKED_BY_RETENTION', 'ERASED', 'CANCELLED'].includes(value.state)) {
        throw new Error('Сервер вернул несогласованную операцию удаления чата.')
    }
    return value
}

export async function getChatErasureOperation(
    chatId: string, signal?: AbortSignal,
): Promise<ChatErasureOperation> {
    return parseOperation(await apiRequest<ChatErasureOperation>(
        `/api/chats/${uuidPathSegment(chatId)}/erasure`, { method: 'GET', signal },
    ), chatId)
}

/** This request is NOT a synchronous deletion. 202 means durable acceptance only. */
export async function requestChatContentErasure(
    chatId: string, title: string, version: number, idempotencyKey: string,
): Promise<ChatErasureOperation> {
    if (!Number.isSafeInteger(version) || version < 0) throw new Error('Недостоверная версия чата.')
    return parseOperation(await apiRequest<ChatErasureOperation>(
        `/api/chats/${uuidPathSegment(chatId)}/erasure`, {
            method: 'DELETE',
            headers: { 'If-Match': `"${version}"`, 'Idempotency-Key': idempotencyKey },
            json: { confirmationTitle: title, confirmationPhrase: 'УДАЛИТЬ НАВСЕГДА' },
        },
    ), chatId)
}

export async function listChatErasureOperations(signal?: AbortSignal): Promise<ChatErasureOperation[]> {
    const values = await apiRequest<ChatErasureOperation[]>('/api/chats/erasure-operations', {
        method: 'GET', signal,
    })
    if (!Array.isArray(values)) throw new Error('Неверная история операций стирания.')
    return values.map(value => parseOperation(value, value?.chatId))
}
