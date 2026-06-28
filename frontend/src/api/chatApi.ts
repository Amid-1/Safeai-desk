// frontend/src/api/chatApi.ts
import { apiRequest } from './http'
import type { PageResponse } from '../utils/page'

export type Chat = {
    id: string
    title: string
    createdAt: string
    updatedAt: string
}

export type ChatMessage = {
    id: string
    role: string
    content: string
    model: string | null
    inputTokens: number | null
    outputTokens: number | null
    costUsd: number | null
    createdAt: string
    status: string
}

export type ChatDetails = {
    id: string
    title: string
    createdAt: string
    updatedAt: string
    messages: ChatMessage[]
}

export async function getChats(
    page = 0,
    size = 50
): Promise<PageResponse<Chat>> {
    return apiRequest<PageResponse<Chat>>(`/api/chats?page=${page}&size=${size}`)
}

export async function getChatById(chatId: string): Promise<ChatDetails> {
    return apiRequest<ChatDetails>(`/api/chats/${chatId}`)
}

export async function createChat(title: string): Promise<Chat> {
    return apiRequest<Chat>('/api/chats', {
        method: 'POST',
        body: JSON.stringify({ title }),
    })
}

export async function sendMessage(
    chatId: string,
    content: string
): Promise<ChatDetails> {
    return apiRequest<ChatDetails>(`/api/chats/${chatId}/messages`, {
        method: 'POST',
        body: JSON.stringify({ content }),
    })
}