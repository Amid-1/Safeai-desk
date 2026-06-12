import { apiRequest } from './http'

export type Chat = {
    id: string
    title: string
    createdAt: string
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
}

export type ChatDetails = {
    id: string
    title: string
    createdAt: string
    messages: ChatMessage[]
}

export async function getChats(): Promise<Chat[]> {
    return apiRequest<Chat[]>('/api/chats')
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

export async function sendMessage(chatId: string, content: string): Promise<ChatDetails> {
    return apiRequest<ChatDetails>(`/api/chats/${chatId}/messages`, {
        method: 'POST',
        body: JSON.stringify({ content }),
    })
}