// frontend/src/features/chat/api/chatApi.http-contract.test.ts
// Регрессионные проверки всех HTTP-методов и публичных ответов chatApi.

import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
    archiveChat, createChat, getAnswerPassport, getChatById,
    getChatCapabilities, getChatMessages, getChatTurnStatus, getChats,
    sendMessage, updateChatTitle,
} from './chatApi'
import { apiRequest, API_TIMEOUTS } from '../../../shared/api/http'
import {
    IDS, assistantMessage, capabilities, chat, passport, slice, succeeded,
    turn, userMessage,
} from '../../../test/fixtures/chatApiFixtures'

vi.mock('../../../shared/api/http', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../../../shared/api/http')>()
    return { ...actual, apiRequest: vi.fn() }
})

const request = vi.mocked(apiRequest)

beforeEach(() => {
    vi.resetAllMocks()
})

describe('chatApi: полный HTTP-контракт', () => {
    it('GET capabilities: проверяет лимиты и передаёт AbortSignal', async () => {
        const signal = new AbortController().signal
        request.mockResolvedValue(capabilities())
        expect(await getChatCapabilities({ signal })).toEqual(capabilities())
        expect(request).toHaveBeenCalledWith('/api/chats/capabilities', {
            method: 'GET', signal, timeoutMs: API_TIMEOUTS.default,
        })
    })

    it('GET chats: использует собственный Slice, а не Spring Page', async () => {
        request.mockResolvedValue(slice([chat()]))
        expect((await getChats()).content).toEqual([chat()])
        expect(request).toHaveBeenCalledWith('/api/chats?page=0&size=50', {
            method: 'GET', signal: undefined, timeoutMs: API_TIMEOUTS.default,
        })
    })

    it.each([
        [-100, 1_000, '/api/chats?page=0&size=100'],
        [2.9, 5.7, '/api/chats?page=2&size=5'],
        [Number.NaN, Number.POSITIVE_INFINITY, '/api/chats?page=0&size=50'],
    ])('нормализует page=%s и size=%s до обращения к серверу', async (page, size, url) => {
        request.mockResolvedValue(slice([], 0))
        await getChats(page, size)
        expect(request).toHaveBeenCalledWith(url, expect.objectContaining({ method: 'GET' }))
    })

    it('GET chat details: парсит вложенную историю и передаёт signal', async () => {
        const signal = new AbortController().signal
        request.mockResolvedValue({ ...chat(), messages: [userMessage(), assistantMessage()] })
        const result = await getChatById(IDS.chat, { signal })
        expect(result.messages).toHaveLength(2)
        expect(result.messages[1]?.usageStatus).toBe('AVAILABLE')
        expect(request).toHaveBeenCalledWith(`/api/chats/${IDS.chat}`, {
            method: 'GET', signal, timeoutMs: API_TIMEOUTS.default,
        })
    })

    it('GET messages: адрес, отдельная пагинация и signal', async () => {
        const signal = new AbortController().signal
        request.mockResolvedValue(slice([userMessage()], 3))
        const result = await getChatMessages(IDS.chat, 3, 20, { signal })
        expect(result.page).toBe(3)
        expect(result.content[0]?.role).toBe('USER')
        expect(request).toHaveBeenCalledWith(`/api/chats/${IDS.chat}/messages?page=3&size=20`, {
            method: 'GET', signal, timeoutMs: API_TIMEOUTS.default,
        })
    })

    it('POST createChat(): отправляет null, не придумывая пользовательский заголовок', async () => {
        request.mockResolvedValue(chat())
        expect((await createChat()).title).toBe('Новый чат')
        expect(request).toHaveBeenCalledWith('/api/chats', {
            method: 'POST', json: { title: null },
            signal: undefined, timeoutMs: API_TIMEOUTS.default,
        })
    })

    it('POST createChat(title): передаёт явно заданный заголовок', async () => {
        request.mockResolvedValue(chat({ title: 'Проверка' }))
        expect((await createChat('Проверка')).title).toBe('Проверка')
        expect(request).toHaveBeenCalledWith('/api/chats', expect.objectContaining({
            method: 'POST', json: { title: 'Проверка' },
        }))
    })

    it('PATCH updateChatTitle: отправляет только title и проверяет ответ', async () => {
        request.mockResolvedValue(chat({ title: 'Изменено' }))
        expect((await updateChatTitle(IDS.chat, 'Изменено')).title).toBe('Изменено')
        expect(request).toHaveBeenCalledWith(`/api/chats/${IDS.chat}`, {
            method: 'PATCH', json: { title: 'Изменено' },
            signal: undefined, timeoutMs: API_TIMEOUTS.default,
        })
    })

    it('DELETE archiveChat: не возвращает придуманного DTO', async () => {
        request.mockResolvedValue(undefined)
        expect(await archiveChat(IDS.chat)).toBeUndefined()
        expect(request).toHaveBeenCalledWith(`/api/chats/${IDS.chat}`, {
            method: 'DELETE', signal: undefined, timeoutMs: API_TIMEOUTS.default,
        })
    })

    it('POST sendMessage: точно передаёт параметры идемпотентности и knowledge mode', async () => {
        const signal = new AbortController().signal
        const payload = {
            content: 'Вопрос', clientRequestId: IDS.request,
            knowledgeBaseId: IDS.knowledgeBase,
            knowledgeMode: 'KNOWLEDGE_ONLY' as const,
        }
        request.mockResolvedValue(succeeded({ replay: true, answerPassport: passport() }))
        const response = await sendMessage(IDS.chat, payload, { signal })
        expect(response.replay).toBe(true)
        expect(response.answerPassport?.citations).toHaveLength(1)
        expect(request).toHaveBeenCalledWith(`/api/chats/${IDS.chat}/messages`, {
            method: 'POST', json: payload, signal, timeoutMs: API_TIMEOUTS.chat,
        })
        expect(API_TIMEOUTS.chat).toBe(95_000)
    })

    it('GET turn status: использует исходный clientRequestId, не turnId', async () => {
        const signal = new AbortController().signal
        request.mockResolvedValue(turn({ state: 'AMBIGUOUS', outcomeAmbiguous: true }))
        const response = await getChatTurnStatus(IDS.chat, IDS.request, { signal })
        expect(response.state).toBe('AMBIGUOUS')
        expect(request).toHaveBeenCalledWith(
            `/api/chats/${IDS.chat}/turns/by-client-request/${IDS.request}`,
            { method: 'GET', signal, timeoutMs: API_TIMEOUTS.default },
        )
    })

    it('GET answer passport: источник истины — durable turnId', async () => {
        const signal = new AbortController().signal
        request.mockResolvedValue(passport())
        const result = await getAnswerPassport(IDS.chat, IDS.turn, { signal })
        expect(result.chatTurnId).toBe(IDS.turn)
        expect(result.citations[0]?.chunkOrdinal).toBe(3)
        expect(request).toHaveBeenCalledWith(
            `/api/chats/${IDS.chat}/turns/${IDS.turn}/answer-passport`,
            { method: 'GET', signal, timeoutMs: API_TIMEOUTS.default },
        )
    })

    it.each([
        ['getChatById', () => getChatById('../incorrect')],
        ['getChatMessages', () => getChatMessages('../incorrect')],
        ['updateChatTitle', () => updateChatTitle('../incorrect', 'test')],
        ['archiveChat', () => archiveChat('../incorrect')],
        ['sendMessage', () => sendMessage('../incorrect', { content: 'test', clientRequestId: IDS.request })],
        ['getChatTurnStatus', () => getChatTurnStatus(IDS.chat, '../incorrect')],
        ['getAnswerPassport', () => getAnswerPassport(IDS.chat, '../incorrect')],
    ] as const)('%s отклоняет неправильный path UUID до вызова API', async (_name, operation) => {
        await expect(operation()).rejects.toThrow()
        expect(request).not.toHaveBeenCalled()
    })

    it('передаёт сетевую ошибку вызывающему коду без превращения в успех', async () => {
        const failure = new Error('Сеть недоступна')
        request.mockRejectedValue(failure)
        await expect(getChats()).rejects.toBe(failure)
    })

    it('не превращает некорректный HTTP-ответ в типизированный успешный DTO', async () => {
        request.mockResolvedValue({ unexpected: true })
        await expect(sendMessage(IDS.chat, { content: 'test', clientRequestId: IDS.request }))
            .rejects.toThrow()
    })
})
