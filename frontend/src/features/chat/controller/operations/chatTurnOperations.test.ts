// frontend/src/features/chat/controller/operations/chatTurnOperations.test.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatTurnOperations.test.ts
// Регрессии отправки, идемпотентности и восстановления AI-операций.
// ============================================================
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatDetails } from '../../api/chatApi'
import type { PendingTurn } from '../../lib/chatPage.helpers'
import { ApiError } from '../../../../shared/api/http'
import { chat, userMessage, assistantMessage, succeeded, IDS } from '../../../../test/fixtures/chatApiFixtures'
import { createTurnUpdates } from './chatTurnUpdates'
import { createTurnFailureHandler } from './chatTurnFailures'
import { createTurnSubmission } from './chatTurnSubmission'
import type { ChatControllerDependencies } from './chatControllerTypes'
import { sendMessage } from '../../api/chatApi'

vi.mock('../../api/chatApi', async (importOriginal) => ({
    ...await importOriginal<typeof import('../../api/chatApi')>(),
    sendMessage: vi.fn(),
}))
vi.mock('../../../../shared/lib/secureUuid', () => ({
    createSecureUuid: vi.fn(() => IDS.request),
}))

const sendMock = vi.mocked(sendMessage)
const pending = (): PendingTurn => ({
    chatId: IDS.chat,
    clientRequestId: IDS.request,
    optimisticMessageId: `pending-${IDS.request}`,
    content: 'hello',
    status: 'SENDING',
    error: null,
    retryAfterUntil: null,
    knowledgeBaseId: null,
    knowledgeMode: 'GENERAL',
})

function harness() {
    let currentChat: ChatDetails | null = { ...chat(), messages: [] }
    let currentChats = [chat()]
    let currentPending: Record<string, PendingTurn> = {}
    let passports = {}
    let drafts: Record<string, string> = { [IDS.chat]: 'hello' }
    let sendError = ''
    const pendingTurnsRef = { current: currentPending }
    const activeChatRef: { current: ChatDetails | null } = { current: currentChat }
    const sendInFlightRef = { current: new Set<string>() }
    const reconciliationControllersRef = { current: new Map<string, AbortController>() }
    const scrollIntentRef = { current: null as ChatControllerDependencies['scrollIntentRef']['current'] }

    const deps = {
        reconciliationControllersRef,
        scrollIntentRef,
        pendingTurnsRef,
        setChats: (next: Parameters<ChatControllerDependencies['setChats']>[0]) => {
            currentChats = typeof next === 'function' ? next(currentChats) : next
        },
        setActiveChat: (next: Parameters<ChatControllerDependencies['setActiveChat']>[0]) => {
            currentChat = typeof next === 'function' ? next(currentChat) : next
            activeChatRef.current = currentChat
        },
        setAnswerPassports: (next: Parameters<ChatControllerDependencies['setAnswerPassports']>[0]) => {
            passports = typeof next === 'function' ? next(passports) : next
        },
        setDrafts: (next: Parameters<ChatControllerDependencies['setDrafts']>[0]) => {
            drafts = typeof next === 'function' ? next(drafts) : next
        },
        setPendingTurns: (next: Parameters<ChatControllerDependencies['setPendingTurns']>[0]) => {
            currentPending = typeof next === 'function' ? next(currentPending) : next
        },
        setSendError: (value: string | ((previous: string) => string)) => {
            sendError = typeof value === 'function' ? value(sendError) : value
        },
    } satisfies Parameters<typeof createTurnUpdates>[0]

    const updates = createTurnUpdates(deps)
    const refreshChatTitle = vi.fn(async () => undefined)
    const reconcilePendingTurn = vi.fn(async () => undefined)
    const failure = createTurnFailureHandler({
        setSendError: deps.setSendError,
        setClock: vi.fn(),
        reconcilePendingTurn,
        putPendingTurn: updates.putPendingTurn,
    })
    const makeSubmission = (overrides: Partial<Pick<ChatControllerDependencies,
        'knowledgeMode' | 'knowledgeBaseId' | 'capabilities'
    >> = {}) => createTurnSubmission({
        capabilities: { maxMessageChars: 16_000, maxChatPageSize: 100,
            maxMessagePageSize: 100, detailsMessageLimit: 50 },
        activeChat: currentChat,
        knowledgeMode: 'GENERAL',
        knowledgeBaseId: '',
        drafts,
        clock: Date.now(),
        pendingTurnsRef,
        activeChatRef,
        scrollIntentRef,
        setSendError: deps.setSendError,
        activePendingTurn: undefined,
        activeChatHasPending: false,
        refreshChatTitle,
        handleSendFailure: failure.handleSendFailure,
        applySendSuccess: updates.applySendSuccess,
        finishPendingTurn: updates.finishPendingTurn,
        setDraft: updates.setDraft,
        putPendingTurn: updates.putPendingTurn,
        sendInFlightRef,
        ...overrides,
    })
    return {
        updates, failure, makeSubmission, reconcilePendingTurn,
        pendingTurnsRef, sendInFlightRef,
        get activeChat() { return currentChat },
        get chats() { return currentChats },
        get pendingTurns() { return currentPending },
        get drafts() { return drafts },
        get sendError() { return sendError },
    }
}

beforeEach(() => {
    vi.clearAllMocks()
})

describe('Контракт отправки и idempotency', () => {
    it('синхронно резервирует pending turn до перерендера React', () => {
        const h = harness()
        h.updates.putPendingTurn(pending())
        expect(h.pendingTurnsRef.current[IDS.chat]?.clientRequestId).toBe(IDS.request)
        expect(h.pendingTurns[IDS.chat]?.clientRequestId).toBe(IDS.request)
        h.updates.finishPendingTurn(IDS.chat)
        expect(h.pendingTurnsRef.current[IDS.chat]).toBeUndefined()
    })

    it('не отправляет два POST при двух последовательных кликах', async () => {
        const h = harness()
        let complete!: (value: ReturnType<typeof succeeded>) => void
        sendMock.mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
        const send = h.makeSubmission()
        const first = send.handleSendMessage()
        const second = send.handleSendMessage()
        expect(sendMock).toHaveBeenCalledTimes(1)
        expect(sendMock).toHaveBeenCalledWith(IDS.chat, expect.objectContaining({
            content: 'hello', clientRequestId: IDS.request,
        }))
        complete(succeeded())
        await Promise.all([first, second])
        expect(h.sendInFlightRef.current.size).toBe(0)
        expect(h.pendingTurnsRef.current[IDS.chat]).toBeUndefined()
    })

    it('отклоняет RAG без базы знаний до выдачи запроса', async () => {
        const h = harness()
        await h.makeSubmission({ knowledgeMode: 'KNOWLEDGE_ONLY' }).handleSendMessage()
        expect(sendMock).not.toHaveBeenCalled()
        expect(h.sendError).toContain('Выберите базу знаний')
    })

    it('отклоняет сообщения за пределом maxMessageChars', async () => {
        const h = harness()
        await h.makeSubmission({
            capabilities: { maxMessageChars: 2, maxChatPageSize: 100,
                maxMessagePageSize: 100, detailsMessageLimit: 50 },
        }).handleSendMessage()
        expect(sendMock).not.toHaveBeenCalled()
        expect(h.sendError).toContain('2 символов')
    })

    it('не завершает pending turn по чужому clientRequestId', () => {
        const h = harness()
        h.updates.putPendingTurn(pending())
        expect(() => h.updates.applySendSuccess(pending(), succeeded({
            clientRequestId: 'other-request',
        }))).toThrow('другого turn')
        expect(h.pendingTurnsRef.current[IDS.chat]).toBeDefined()
    })

    it('после правильного ответа объединяет сообщения без потери предыдущих', () => {
        const h = harness()
        h.updates.putPendingTurn(pending())
        h.updates.applySendSuccess(pending(), succeeded({
            userMessage: userMessage(), assistantMessage: assistantMessage(),
        }))
        expect(h.activeChat?.messages).toHaveLength(2)
        expect(h.chats[0]?.id).toBe(IDS.chat)
        expect(h.pendingTurnsRef.current[IDS.chat]).toBeUndefined()
    })
})

describe('Классификация ошибок отправки', () => {
    it.each([
        ['AI_OUTCOME_AMBIGUOUS', 'AMBIGUOUS'],
        ['CHAT_PROCESSOR_FENCED', 'AMBIGUOUS'],
        ['CHAT_ACCESS_REVOKED_DURING_PROCESSING', 'ACCESS_REVOKED'],
        ['IDEMPOTENCY_KEY_REUSED', 'IDEMPOTENCY_CONFLICT'],
        ['AI_QUOTA_EXCEEDED', 'QUOTA_BLOCKED'],
        ['CHAT_QUOTA_EXCEEDED', 'QUOTA_BLOCKED'],
    ] as const)('%s → %s, не запускает небезопасный повтор', async (code, expected) => {
        const h = harness()
        await h.failure.handleSendFailure(
            pending(), new ApiError(code, { code }, 409),
        )
        expect(h.pendingTurnsRef.current[IDS.chat]?.status).toBe(expected)
        expect(h.reconcilePendingTurn).not.toHaveBeenCalled()
    })

    it('при CHAT_BUSY восстанавливает исходный turn, не выдавая новый requestId', async () => {
        const h = harness()
        await h.failure.handleSendFailure(
            pending(), new ApiError('busy', { code: 'CHAT_BUSY' }, 409),
        )
        expect(h.reconcilePendingTurn).toHaveBeenCalledWith(pending())
        expect(h.pendingTurnsRef.current[IDS.chat]?.status).toBe('PROCESSING')
    })

    it('после сетевой ошибки сохраняет исходный clientRequestId и делает reconcile', async () => {
        const h = harness()
        await h.failure.handleSendFailure(pending(), new Error('network'))
        expect(h.pendingTurnsRef.current[IDS.chat]?.clientRequestId).toBe(IDS.request)
        expect(h.pendingTurnsRef.current[IDS.chat]?.status).toBe('SEND_UNKNOWN')
        expect(h.reconcilePendingTurn).toHaveBeenCalledTimes(1)
    })
})
