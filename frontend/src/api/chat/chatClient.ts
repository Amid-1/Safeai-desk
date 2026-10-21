// frontend/src/api/chat/chatClient.ts
// Только HTTP-вызовы; маршруты, payload и timeout не изменены.

import { API_TIMEOUTS, apiRequest } from '../http'
import { buildQueryString, normalizePage, normalizePageSize, uuidPathSegment } from '../query'
import type {
    AnswerPassport, Chat, ChatCapabilities, ChatDetails, ChatMessage,
    ChatSliceResponse, ChatTurnStatus, SendMessageRequest, SendMessageResponse,
} from './chatTypes'
import { parseChat, parseChatCapabilities, parseChatDetails, parseChatSliceResponse } from './chatCoreParsers'
import { parseChatMessage } from './chatMessageParser'
import { parseSendMessageResponse, parseChatTurnStatus } from './chatTurnParsers'
import { parseAnswerPassport } from './chatPassportParser'

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
    title: string | null = null,
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

export async function updateChatTitle(
    chatId: string,
    title: string,
    options: RequestOptions = {},
): Promise<Chat> {
    const response = await apiRequest<unknown>(
        `/api/chats/${uuidPathSegment(chatId)}`,
        {
            method: 'PATCH',
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
 * Загружает неизменяемый паспорт ответа отдельно от отправки сообщения.
 * Он нужен для восстановления вкладки после потери SendMessageResponse.
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
