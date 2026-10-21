// frontend/src/features/chat/controller/operations/chatTurnReconciliation.test.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatTurnReconciliation.test.ts
// Проверки исходного clientRequestId и состояний reconciliation.
// ============================================================
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PendingTurn } from '../../lib/chatPage.helpers'
import { getChatTurnStatus, getChatById } from '../../api/chatApi'
import { ApiError } from '../../../../shared/api/http'
import { IDS, assistantMessage, chat, turn } from '../../../../test/fixtures/chatApiFixtures'
import { createTurnReconciliation } from './chatTurnReconciliation'

vi.mock('../../api/chatApi', async (importOriginal) => ({
    ...await importOriginal<typeof import('../../api/chatApi')>(),
    getChatTurnStatus: vi.fn(), getChatById: vi.fn(),
}))

const getTurnMock = vi.mocked(getChatTurnStatus)
const getDetailsMock = vi.mocked(getChatById)
const pending: PendingTurn = {
    chatId: IDS.chat, clientRequestId: IDS.request,
    optimisticMessageId: `pending-${IDS.request}`,
    content: 'hello', status: 'SEND_UNKNOWN', error: null,
    retryAfterUntil: null, knowledgeBaseId: null, knowledgeMode: 'GENERAL',
}

function mount() {
    const controllers = { current: new Map<string, AbortController>() }
    const applyTurnMessages = vi.fn()
    const applyTurnSuccess = vi.fn()
    const applyReloadedSuccess = vi.fn()
    const refreshChatTitle = vi.fn(async () => undefined)
    const putPendingTurn = vi.fn()
    const setSendError = vi.fn()
    const { reconcilePendingTurn } = createTurnReconciliation({
        reconciliationControllersRef: controllers,
        setSendError,
        refreshChatTitle,
        applyTurnSuccess,
        applyReloadedSuccess,
        applyTurnMessages,
        putPendingTurn,
    })
    return { controllers, reconcilePendingTurn, applyTurnMessages,
        applyTurnSuccess, applyReloadedSuccess, refreshChatTitle,
        putPendingTurn, setSendError }
}

beforeEach(() => {
    vi.clearAllMocks()
})

describe('Reconciliation состояния AI-turn', () => {
    it('сохраняет idempotency key и принимает завершённый turn только для исходной операции', async () => {
        getTurnMock.mockResolvedValue(turn({
            state: 'SUCCEEDED', assistantMessage: assistantMessage(),
        }))
        const h = mount()
        await h.reconcilePendingTurn(pending)
        expect(getTurnMock).toHaveBeenCalledWith(
            IDS.chat, IDS.request, expect.objectContaining({ signal: expect.any(AbortSignal) }),
        )
        expect(h.applyTurnSuccess).toHaveBeenCalledTimes(1)
        expect(h.refreshChatTitle).toHaveBeenCalledWith(IDS.chat)
        expect(h.controllers.current.size).toBe(0)
    })

    it('при SUCCEEDED без сообщений догружает snapshot чата', async () => {
        getTurnMock.mockResolvedValue(turn({ state: 'SUCCEEDED', assistantMessage: null }))
        getDetailsMock.mockResolvedValue({ ...chat(), messages: [] })
        const h = mount()
        await h.reconcilePendingTurn(pending)
        expect(h.applyReloadedSuccess).toHaveBeenCalledWith(pending, expect.objectContaining({ id: IDS.chat }))
    })

    it('сохраняет FAILED как терминальный результат без повторного POST', async () => {
        getTurnMock.mockResolvedValue(turn({ state: 'FAILED', failureCode: 'PROVIDER_FAILURE' }))
        const h = mount()
        await h.reconcilePendingTurn(pending)
        expect(h.putPendingTurn).toHaveBeenCalledWith(expect.objectContaining({
            chatId: IDS.chat, clientRequestId: IDS.request, status: 'FAILED',
        }))
    })

    it('не трактует AMBIGUOUS как разрешение сделать новый запрос', async () => {
        getTurnMock.mockResolvedValue(turn({ state: 'AMBIGUOUS', outcomeAmbiguous: true }))
        const h = mount()
        await h.reconcilePendingTurn(pending)
        expect(h.putPendingTurn).toHaveBeenCalledWith(expect.objectContaining({
            status: 'AMBIGUOUS', clientRequestId: IDS.request,
        }))
    })

    it('отклоняет состояние, относящееся к другому clientRequestId', async () => {
        getTurnMock.mockResolvedValue(turn({
            state: 'SUCCEEDED',
            clientRequestId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
            assistantMessage: assistantMessage(),
        }))
        const h = mount()
        await h.reconcilePendingTurn(pending)
        expect(h.applyTurnMessages).not.toHaveBeenCalled()
        expect(h.applyTurnSuccess).not.toHaveBeenCalled()
        expect(h.putPendingTurn).toHaveBeenCalledWith(expect.objectContaining({
            status: 'AMBIGUOUS', clientRequestId: IDS.request,
        }))
    })

    it('при 404 сохраняет неизвестный исход и прежний requestId', async () => {
        getTurnMock.mockRejectedValue(new ApiError('Не найден', { code: 'NOT_FOUND' }, 404))
        const h = mount()
        await h.reconcilePendingTurn(pending)
        expect(h.putPendingTurn).toHaveBeenCalledWith(expect.objectContaining({
            status: 'SEND_UNKNOWN', clientRequestId: IDS.request,
        }))
    })

    it('не применяет запоздавший успешный ответ отменённого контроллера', async () => {
        let resolveTurn!: (value: ReturnType<typeof turn>) => void
        getTurnMock.mockImplementationOnce(() => new Promise(resolve => { resolveTurn = resolve }))
        const h = mount()
        const running = h.reconcilePendingTurn(pending)
        const previousController = h.controllers.current.get(IDS.chat)
        expect(previousController).toBeDefined()
        previousController?.abort()
        resolveTurn(turn({ state: 'SUCCEEDED', assistantMessage: assistantMessage() }))
        await running
        expect(h.applyTurnMessages).not.toHaveBeenCalled()
        expect(h.applyTurnSuccess).not.toHaveBeenCalled()
    })
})
