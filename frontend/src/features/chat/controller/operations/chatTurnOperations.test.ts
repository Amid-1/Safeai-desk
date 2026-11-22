// frontend/src/features/chat/controller/operations/chatTurnOperations.test.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatTurnOperations.test.ts
// Регрессии отправки, идемпотентности и восстановления AI-операций.
// ============================================================
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatDetails } from '../../api/chatApi'
import type { PendingTurn } from '../../lib/chatPage.helpers'
import { createPendingTurn } from '../../lib/chatPage.helpers'
import { applyReactStateUpdate } from '../../../../test/fixtures/applyReactStateUpdate'
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
    // vi.mock is hoisted before module imports: avoid accessing fixture IDS in its factory.
    createSecureUuid: vi.fn(() => 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
}))

const sendMock = vi.mocked(sendMessage)
const pending = (): PendingTurn =>
    createPendingTurn(IDS.chat, 'hello', IDS.request)

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
            currentChats = applyReactStateUpdate(currentChats, next)
        },
        setActiveChat: (next: Parameters<ChatControllerDependencies['setActiveChat']>[0]) => {
            currentChat = applyReactStateUpdate(currentChat, next)
            activeChatRef.current = currentChat
        },
        setAnswerPassports: (next: Parameters<ChatControllerDependencies['setAnswerPassports']>[0]) => {
            passports = applyReactStateUpdate(passports, next)
        },
        setDrafts: (next: Parameters<ChatControllerDependencies['setDrafts']>[0]) => {
            drafts = applyReactStateUpdate(drafts, next)
        },
        setPendingTurns: (next: Parameters<ChatControllerDependencies['setPendingTurns']>[0]) => {
            currentPending = applyReactStateUpdate(currentPending, next)
        },
        setSendError: (value: string | ((previous: string) => string)) => {
            sendError = applyReactStateUpdate(sendError, value)
        },
    } satisfies Parameters<typeof createTurnUpdates>[0]

    const updates = createTurnUpdates(deps)
    const refreshChatTitle = vi.fn(async () => undefined)
    const ensureAutomaticChatTitle = vi.fn(async () => undefined)
    const reconcilePendingTurn = vi.fn(async () => undefined)
    const failure = createTurnFailureHandler({
        activeChatRef,
        setSendError: deps.setSendError,
        setClock: vi.fn(),
        reconcilePendingTurn,
        putPendingTurn: updates.putPendingTurn,
    })
    const makeSubmission = (overrides: Partial<Pick<ChatControllerDependencies,
        'knowledgeMode' | 'knowledgeBaseId' | 'knowledgeBases' | 'knowledgeBasesStatus'
        | 'capabilities' | 'activePendingTurn'
    >> = {}) => createTurnSubmission({
        capabilities: { maxMessageChars: 16_000, maxChatPageSize: 100,
            maxMessagePageSize: 100, detailsMessageLimit: 50 },
        activeChat: currentChat,
        knowledgeMode: 'GENERAL',
        knowledgeBaseId: '',
        knowledgeBases: [],
        knowledgeBasesStatus: 'ready',
        drafts,
        clock: Date.now(),
        pendingTurnsRef,
        activeChatRef,
        scrollIntentRef,
        setSendError: deps.setSendError,
        activePendingTurn: undefined,
        activeChatHasPending: false,
        refreshChatTitle,
        ensureAutomaticChatTitle,
        handleSendFailure: failure.handleSendFailure,
        applySendSuccess: updates.applySendSuccess,
        finishPendingTurn: updates.finishPendingTurn,
        setDraft: updates.setDraft,
        putPendingTurn: updates.putPendingTurn,
        sendInFlightRef,
        reconciliationControllersRef,
        ...overrides,
    })
    return {
        updates, failure, makeSubmission, reconcilePendingTurn, ensureAutomaticChatTitle,
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
        expect(h.sendError).toContain('Выберите доступную базу знаний')
        expect(h.drafts[IDS.chat]).toBe('hello')
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
    it('показывает понятную причину блокировки и номер решения без повтора POST', async () => {
        const h = harness()
        const id = 'e933230e-1ef2-47e5-ab06-4b67adb82393'
        await h.failure.handleSendFailure(pending(), new ApiError(
            'AI model route отклонён политикой SafeAI: MODEL_NOT_ALLOWED. decisionId=' + id,
            { code: 'FORBIDDEN' }, 403,
        ))
        expect(h.sendError).toContain('Модель не разрешена для вашей организации')
        expect(h.sendError).toContain(id)
        expect(h.pendingTurns[IDS.chat]?.status).toBe('FAILED')
        expect(h.reconcilePendingTurn).not.toHaveBeenCalled()
        expect(sendMock).not.toHaveBeenCalled()
    })
    it('явный отказ авторизации не маскируется как неопределённая отправка', async () => {
        const h = harness()
        await h.failure.handleSendFailure(
            pending(), new ApiError('Нет доступа к модели', { code: 'FORBIDDEN' }, 403),
        )
        expect(h.pendingTurnsRef.current[IDS.chat]?.status).toBe('FAILED')
        expect(h.reconcilePendingTurn).not.toHaveBeenCalled()
    })

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
        expect(h.reconcilePendingTurn).toHaveBeenCalledExactlyOnceWith(
            h.pendingTurnsRef.current[IDS.chat],
        )
    })

    it('возвращает RATE_LIMITED и сохраняет исходный clientRequestId', async () => {
        const h = harness()
        await h.failure.handleSendFailure(
            pending(), new ApiError('Too many requests', { code: 'RATE_LIMIT_EXCEEDED', retryAfterSeconds: 3 }, 429),
        )
        expect(h.pendingTurnsRef.current[IDS.chat]).toEqual(expect.objectContaining({
            clientRequestId: IDS.request, status: 'RATE_LIMITED',
        }))
        expect(h.reconcilePendingTurn).not.toHaveBeenCalled()
        expect(sendMock).not.toHaveBeenCalled()
    })

    it('при требовании сверки выполняет только GET исходного запроса', async () => {
        const h = harness()
        await h.failure.handleSendFailure(pending(), new ApiError(
            'internal attempt details', { code: 'AI_EXECUTION_RECONCILIATION_REQUIRED' }, 409,
        ))
        const current = h.pendingTurnsRef.current[IDS.chat]
        expect(current.status).toBe('RECONCILIATION_REQUIRED')
        expect(current.clientRequestId).toBe(IDS.request)
        expect(current.error).not.toContain('internal')
        expect(h.reconcilePendingTurn).toHaveBeenCalledWith(current)
        const actions = h.makeSubmission({ activePendingTurn: current })
        await actions.retryPendingTurn()
        actions.prepareNewRequestFromPending()
        actions.dismissUnsafePending()
        expect(sendMock).not.toHaveBeenCalled()
        expect(h.pendingTurnsRef.current[IDS.chat]).toEqual(current)
    })

    it('не доверяет старому обработчику кнопки после изменения состояния', async () => {
        const h = harness()
        const previous = { ...pending(), status: 'SEND_UNKNOWN' as const }
        h.updates.putPendingTurn(previous)
        const actions = h.makeSubmission({ activePendingTurn: previous })
        h.updates.putPendingTurn({ ...previous, status: 'RECONCILIATION_REQUIRED' })
        await actions.retryPendingTurn()
        expect(sendMock).not.toHaveBeenCalled()
    })

    it('429 без Retry-After запрещает ранний повтор и новый идентификатор', async () => {
        const h = harness()
        await h.failure.handleSendFailure(pending(), new ApiError('private', {}, 429))
        const current = h.pendingTurnsRef.current[IDS.chat]
        expect(current.retryAfterUntil).toBeGreaterThan(Date.now() + 28_000)
        const actions = h.makeSubmission({ activePendingTurn: current })
        await actions.retryPendingTurn()
        actions.prepareNewRequestFromPending()
        expect(sendMock).not.toHaveBeenCalled()
        expect(h.pendingTurnsRef.current[IDS.chat]).toEqual(current)
    })
})
