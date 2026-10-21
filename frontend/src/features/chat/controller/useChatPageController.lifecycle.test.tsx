// frontend/src/features/chat/controller/useChatPageController.lifecycle.test.tsx
// ============================================================
// frontend/src/features/chat/controller/useChatPageController.lifecycle.test.tsx
// Интеграционные регрессии жизненного цикла, загрузки и отправки чата.
// ============================================================
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ChatDetails } from '../api/chatApi'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
    getAnswerPassport,
    getChatById,
    getChatCapabilities,
    getChatMessages,
    getChatTurnStatus,
    getChats,
    sendMessage,
} from '../api/chatApi'
import { getKnowledgeBases } from '../../../entities/knowledge/api/knowledgeApi'
import {
    assistantMessage, capabilities, chat, IDS, passport,
    slice, succeeded, turn, userMessage,
} from '../../../test/fixtures/chatApiFixtures'
import { useChatPageController } from './useChatPageController'

vi.mock('../api/chatApi', async (importOriginal) => ({
    ...await importOriginal<typeof import('../api/chatApi')>(),
    getChatById: vi.fn(),
    getChatCapabilities: vi.fn(),
    getChatMessages: vi.fn(),
    getChats: vi.fn(),
    getChatTurnStatus: vi.fn(),
    getAnswerPassport: vi.fn(),
    sendMessage: vi.fn(),
}))
vi.mock('../../../shared/lib/secureUuid', () => ({
    createSecureUuid: () => 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
}))
vi.mock('../../../entities/knowledge/api/knowledgeApi', async (importOriginal) => ({
    ...await importOriginal<typeof import('../../../entities/knowledge/api/knowledgeApi')>(),
    getKnowledgeBases: vi.fn(),
}))

const getChatsMock = vi.mocked(getChats)
const getChatByIdMock = vi.mocked(getChatById)
const getMessagesMock = vi.mocked(getChatMessages)
const getCapabilitiesMock = vi.mocked(getChatCapabilities)
const getKnowledgeBasesMock = vi.mocked(getKnowledgeBases)
const sendMock = vi.mocked(sendMessage)
const getPassportMock = vi.mocked(getAnswerPassport)
const getTurnStatusMock = vi.mocked(getChatTurnStatus)

beforeEach(() => {
    vi.clearAllMocks()
    getCapabilitiesMock.mockResolvedValue(capabilities())
    getChatsMock.mockResolvedValue(slice([chat()]))
    getChatByIdMock.mockResolvedValue({ ...chat(), messages: [] })
    getMessagesMock.mockResolvedValue(slice([]))
    getTurnStatusMock.mockResolvedValue(turn({ state: 'NEW' }))
    getKnowledgeBasesMock.mockResolvedValue({
        content: [], page: 0, size: 100, totalElements: 0, totalPages: 0,
    })
})

describe('useChatPageController — жизненный цикл', () => {
    it('загружает первый Slice и сохраняет прежнюю форму публичного контроллера', async () => {
        const { result } = renderHook(() => useChatPageController())
        await waitFor(() => expect(result.current.activeChat?.id).toBe(IDS.chat))
        expect(getChatsMock).toHaveBeenCalledWith(0, 50,
            expect.objectContaining({ signal: expect.any(AbortSignal) }))
        expect(getMessagesMock).toHaveBeenCalledWith(IDS.chat, 0, 50,
            expect.objectContaining({ signal: expect.any(AbortSignal) }))
        expect(result.current.chats).toHaveLength(1)
        expect(result.current.chatHasNext).toBe(false)
        expect(typeof result.current.handleSendMessage).toBe('function')
        expect(typeof result.current.reconcilePendingTurn).toBe('function')
        expect(typeof result.current.loadEarlierHistory).toBe('function')
    })

    it('не принимает ответ старого openChat после переключения на другой чат', async () => {
        getChatsMock.mockResolvedValue(slice([]))
        const another = '99999999-9999-4999-8999-999999999999'
        let finishFirst!: (value: ChatDetails) => void
        getChatByIdMock.mockImplementation((id) => id === IDS.chat
            ? new Promise(resolve => { finishFirst = resolve })
            : Promise.resolve({ ...chat({ id }), messages: [] }))
        const { result } = renderHook(() => useChatPageController())
        await waitFor(() => expect(result.current.chatsLoading).toBe(false))
        let openingFirst!: Promise<void>
        act(() => { openingFirst = result.current.openChat(IDS.chat) })
        await act(async () => { await result.current.openChat(another) })
        finishFirst({ ...chat(), messages: [] })
        await act(async () => { await openingFirst })
        expect(result.current.activeChat?.id).toBe(another)
    })

    it('завершает штатную отправку без повторного сообщения и оставшегося pending', async () => {
        getMessagesMock.mockResolvedValue(slice([userMessage()]))
        sendMock.mockResolvedValue(succeeded({
            userMessage: userMessage(), assistantMessage: assistantMessage(),
        }))
        const { result } = renderHook(() => useChatPageController())
        await waitFor(() => expect(result.current.activeChat?.id).toBe(IDS.chat))
        act(() => { result.current.setDraft(IDS.chat, 'hello') })
        await act(async () => { await result.current.handleSendMessage() })
        expect(sendMock).toHaveBeenCalledTimes(1)
        expect(sendMock).toHaveBeenCalledWith(IDS.chat, expect.objectContaining({
            clientRequestId: expect.any(String), content: 'hello',
        }))
        expect(result.current.pendingTurns[IDS.chat]).toBeUndefined()
        expect(result.current.activeChat?.messages.some(m => m.id === IDS.assistantMessage)).toBe(true)
    })

    it('не привязывает чужой answer passport к сообщению текущего чата', async () => {
        getMessagesMock.mockResolvedValue(slice([userMessage()]))
        getTurnStatusMock.mockResolvedValue(turn({
            state: 'SUCCEEDED', assistantMessage: assistantMessage(),
        }))
        getPassportMock.mockResolvedValue(passport({
            chatTurnId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
        }))
        const { result } = renderHook(() => useChatPageController())
        await waitFor(() => expect(getPassportMock).toHaveBeenCalled())
        await act(async () => { await Promise.resolve() })
        expect(result.current.answerPassports[IDS.assistantMessage]).toBeUndefined()
    })

    it('оставляет чат доступным в GENERAL при ошибке Knowledge API', async () => {
        getKnowledgeBasesMock.mockRejectedValue(new Error('Knowledge offline'))
        const { result } = renderHook(() => useChatPageController())
        await waitFor(() => expect(result.current.activeChat?.id).toBe(IDS.chat))
        expect(result.current.knowledgeBases).toEqual([])
        expect(result.current.knowledgeMode).toBe('GENERAL')
    })

    it('прерывает незавершённые GET при размонтировании компонента', async () => {
        getChatsMock.mockImplementation(() => new Promise(() => undefined))
        const { unmount } = renderHook(() => useChatPageController())
        await waitFor(() => expect(getChatsMock).toHaveBeenCalled())
        const signal = getChatsMock.mock.calls.at(-1)?.[2]?.signal
        expect(signal?.aborted).toBe(false)
        unmount()
        expect(signal?.aborted).toBe(true)
    })
})
