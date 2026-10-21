// frontend/src/features/chat/controller/operations/chatHistoryActions.test.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatHistoryActions.test.ts
// Проверки пагинации истории, отмены запросов и порядка сообщений.
// ============================================================
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatDetails } from '../../api/chatApi'
import { getChatMessages } from '../../api/chatApi'
import { chat, IDS, slice, userMessage } from '../../../../test/fixtures/chatApiFixtures'
import { createHistoryActions } from './chatHistoryActions'

vi.mock('../../api/chatApi', async (importOriginal) => ({
    ...await importOriginal<typeof import('../../api/chatApi')>(),
    getChatMessages: vi.fn(),
}))
const messagesMock = vi.mocked(getChatMessages)

function mount() {
    let active: ChatDetails | null = { ...chat(), messages: [] }
    let historyPage = 1
    let hasNext = true
    let historyLoading = false
    let chatError = ''
    const activeChatRef: { current: ChatDetails | null } = { current: active }
    const scrollIntentRef = { current: null as {type:'PRESERVE_TOP';scrollHeight:number;scrollTop:number} | {type:'BOTTOM'} | null }
    const container = { scrollHeight: 800, scrollTop: 120 } as HTMLDivElement
    const historyControllerRef = { current: null as AbortController | null }
    const historyRequestSequenceRef = { current: 0 }
    const restoreAnswerPassports = vi.fn(async () => undefined)
    const h = createHistoryActions({
        historyLoading,
        historyPage,
        historyHasNext: hasNext,
        messagesContainerRef: { current: container },
        historyRequestSequenceRef,
        historyControllerRef,
        activeChatRef,
        scrollIntentRef,
        setActiveChat(next) {
            active = typeof next === 'function' ? next(active) : next
            activeChatRef.current = active
        },
        setChatError(next) { chatError = typeof next === 'function' ? next(chatError) : next },
        setHistoryLoading(next) { historyLoading = typeof next === 'function' ? next(historyLoading) : next },
        setHistoryPage(next) { historyPage = typeof next === 'function' ? next(historyPage) : next },
        setHistoryHasNext(next) { hasNext = typeof next === 'function' ? next(hasNext) : next },
        messagePageSize: 50,
        restoreAnswerPassports,
    })
    return { ...h, activeChatRef, scrollIntentRef, restoreAnswerPassports,
        get active() { return active }, get page() { return historyPage },
        get error() { return chatError }, get loading() { return historyLoading },
        get hasNext() { return hasNext } }
}

beforeEach(() => vi.clearAllMocks())

describe('Пагинация истории', () => {
    it('дозагружает прошлые сообщения и сохраняет позицию прокрутки', async () => {
        messagesMock.mockResolvedValue({ ...slice([userMessage()], 1), hasNext: false })
        const h = mount()
        await h.loadEarlierHistory()
        expect(messagesMock).toHaveBeenCalledWith(IDS.chat, 1, 50,
            expect.objectContaining({ signal: expect.any(AbortSignal) }))
        expect(h.active?.messages).toHaveLength(1)
        expect(h.page).toBe(2)
        expect(h.hasNext).toBe(false)
        expect(h.scrollIntentRef.current).toEqual({ type: 'PRESERVE_TOP', scrollHeight: 800, scrollTop: 120 })
        expect(h.restoreAnswerPassports).toHaveBeenCalledWith(IDS.chat,
            expect.any(Array), expect.any(AbortSignal))
    })

    it('игнорирует страницу прежнего чата после смены activeChat', async () => {
        let resolvePage!: (value: ReturnType<typeof slice<ReturnType<typeof userMessage>>>) => void
        messagesMock.mockImplementationOnce(() => new Promise(resolve => { resolvePage = resolve }))
        const h = mount()
        const running = h.loadEarlierHistory()
        h.activeChatRef.current = { ...chat({ id: 'other-chat' }), messages: [] }
        resolvePage(slice([userMessage()], 1))
        await running
        expect(h.active?.messages).toHaveLength(0)
        expect(h.restoreAnswerPassports).not.toHaveBeenCalled()
    })

    it('при сетевой ошибке показывает ошибку, не очищая прежние сообщения', async () => {
        messagesMock.mockRejectedValue(new Error('offline'))
        const h = mount()
        await h.loadEarlierHistory()
        expect(h.error).toContain('Не удалось загрузить историю сообщений')
        expect(h.active?.messages).toHaveLength(0)
        expect(h.loading).toBe(false)
    })
})
