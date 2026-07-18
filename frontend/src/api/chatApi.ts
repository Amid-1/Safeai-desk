import { apiRequest } from './http'
import { buildQueryString, normalizePage, normalizePageSize, pathSegment } from './query'
import type { ChatMessageRole, ChatMessageStatus } from './types'
import type { PageResponse } from '../utils/page'

export type Chat = {
    id: string
    title: string
    createdAt: string
    updatedAt: string
}

export type ChatMessage = {
    id: string
    role: ChatMessageRole
    content: string
    model: string | null
    inputTokens: number | null
    outputTokens: number | null
    costUsd: number | null
    createdAt: string
    status: ChatMessageStatus
}

export type ChatDetails = Chat & {
    messages: ChatMessage[]
}

export function getChats(page = 0, size = 50): Promise<PageResponse<Chat>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(size, 50, 200),
    })
    return apiRequest<PageResponse<Chat>>(`/api/chats${query}`, { timeoutMs: 30_000 })
}

export function getChatById(chatId: string): Promise<ChatDetails> {
    return apiRequest<ChatDetails>(`/api/chats/${pathSegment(chatId)}`, { timeoutMs: 30_000 })
}

export function getChatMessages(
    chatId: string,
    page = 0,
    size = 50
): Promise<PageResponse<ChatMessage>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(size, 50, 100),
    })
    return apiRequest<PageResponse<ChatMessage>>(
        `/api/chats/${pathSegment(chatId)}/messages${query}`,
        { timeoutMs: 30_000 }
    )
}

export function createChat(title: string): Promise<Chat> {
    return apiRequest<Chat>('/api/chats', {
        method: 'POST',
        body: JSON.stringify({ title }),
        timeoutMs: 30_000,
    })
}

export function sendMessage(chatId: string, content: string): Promise<ChatDetails> {
    return apiRequest<ChatDetails>(`/api/chats/${pathSegment(chatId)}/messages`, {
        method: 'POST',
        body: JSON.stringify({ content }),
        timeoutMs: 80_000,
    })
}