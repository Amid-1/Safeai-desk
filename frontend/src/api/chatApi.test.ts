// ============================================================
// frontend/src/api/chatApi.test.ts
// ============================================================
import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    archiveChat,
    getAnswerPassport,
    getChatTurnStatus,
    getChats,
    parseChatMessage,
    parseChatTurnStatus,
    sendMessage,
} from './chatApi'
import {
    apiRequest,
} from './http'

vi.mock('./http', async (importOriginal) => {
    const actual =
        await importOriginal<
            typeof import('./http')
        >()

    return {
        ...actual,
        apiRequest: vi.fn(),
    }
})

const requestMock = vi.mocked(apiRequest)

const CHAT_ID =
    '33333333-3333-4333-8333-333333333333'
const TURN_ID =
    '44444444-4444-4444-8444-444444444444'
const REQUEST_ID =
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const OPERATION_ID =
    '55555555-5555-4555-8555-555555555555'
const USER_MESSAGE_ID =
    '11111111-1111-4111-8111-111111111111'
const ASSISTANT_MESSAGE_ID =
    '22222222-2222-4222-8222-222222222222'

const USER_MESSAGE = {
    id: USER_MESSAGE_ID,
    clientRequestId: REQUEST_ID,
    replyToMessageId: null,
    role: 'USER',
    status: 'COMPLETED',
    content: 'hello',
    requestedModel: null,
    model: null,
    providerMessageId: null,
    providerRequestId: null,
    aiResponseStatus: null,
    finishReason: null,
    inputTokens: null,
    outputTokens: null,
    usageStatus: 'NOT_APPLICABLE',
    costUsd: null,
    pricingStatus: 'NOT_APPLICABLE',
    currency: null,
    pricingVersion: null,
    pricingCalculatedAt: null,
    createdAt: '2026-08-11T10:00:00Z',
}

const ASSISTANT_MESSAGE = {
    id: ASSISTANT_MESSAGE_ID,
    clientRequestId: null,
    replyToMessageId: USER_MESSAGE_ID,
    role: 'ASSISTANT',
    status: 'COMPLETED',
    content: 'answer',
    requestedModel: 'mock-safeai',
    model: 'mock-safeai',
    providerMessageId: null,
    providerRequestId: 'provider-request',
    aiResponseStatus: 'COMPLETED',
    finishReason: 'stop',
    inputTokens: 10,
    outputTokens: 5,
    usageStatus: 'AVAILABLE',
    costUsd: '0',
    pricingStatus: 'FREE',
    currency: 'USD',
    pricingVersion: 'mock',
    pricingCalculatedAt: '2026-08-11T10:00:01Z',
    createdAt: '2026-08-11T10:00:01Z',
}

describe('chatApi production contract', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('parses custom Slice response instead of Spring Page response', async () => {
        requestMock.mockResolvedValue({
            content: [
                {
                    id: CHAT_ID,
                    title: 'Chat',
                    createdAt: '2026-08-11T10:00:00Z',
                    updatedAt: '2026-08-11T10:00:01Z',
                },
            ],
            page: 0,
            size: 50,
            first: true,
            last: true,
            hasNext: false,
            hasPrevious: false,
        })

        const response = await getChats()

        expect(response.content).toHaveLength(1)
        expect(response.hasNext).toBe(false)
        expect(response.page).toBe(0)
    })

    it('archives a chat through the tenant-scoped endpoint', async () => {
        requestMock.mockResolvedValue(undefined)

        await archiveChat(CHAT_ID)

        expect(requestMock).toHaveBeenCalledWith(
            `/api/chats/${CHAT_ID}`,
            expect.objectContaining({
                method: 'DELETE',
            }),
        )
    })

    it('sendMessage parses SendMessageResponse, not ChatDetails', async () => {
        requestMock.mockResolvedValue({
            chatId: CHAT_ID,
            turnId: TURN_ID,
            clientRequestId: REQUEST_ID,
            providerOperationId: OPERATION_ID,
            state: 'SUCCEEDED',
            replay: false,
            userMessage: USER_MESSAGE,
            assistantMessage: ASSISTANT_MESSAGE,
            chatUpdatedAt: '2026-08-11T10:00:01Z',
            createdAt: '2026-08-11T10:00:00Z',
            completedAt: '2026-08-11T10:00:01Z',
        })

        const response = await sendMessage(
            CHAT_ID,
            {
                content: 'hello',
                clientRequestId: REQUEST_ID,
            },
        )

        expect(response.turnId).toBe(TURN_ID)
        expect(response.userMessage.id).toBe(
            USER_MESSAGE_ID,
        )
        expect(response.assistantMessage.id).toBe(
            ASSISTANT_MESSAGE_ID,
        )

        expect(requestMock).toHaveBeenCalledWith(
            `/api/chats/${CHAT_ID}/messages`,
            expect.objectContaining({
                method: 'POST',
                json: {
                    content: 'hello',
                    clientRequestId: REQUEST_ID,
                },
                timeoutMs: 95_000,
            }),
        )
    })

    it('uses canonical turn-by-client-request endpoint', async () => {
        requestMock.mockResolvedValue({
            chatId: CHAT_ID,
            turnId: TURN_ID,
            clientRequestId: REQUEST_ID,
            providerOperationId: OPERATION_ID,
            state: 'PROCESSING',
            provider: 'mock',
            requestedModel: 'mock-safeai',
            resolvedModel: null,
            providerRequestId: null,
            providerErrorType: null,
            failureCode: null,
            outcomeAmbiguous: false,
            leaseUntil: '2026-08-11T10:03:00Z',
            providerCallStartedAt: null,
            createdAt: '2026-08-11T10:00:00Z',
            updatedAt: '2026-08-11T10:00:01Z',
            completedAt: null,
            userMessage: USER_MESSAGE,
            assistantMessage: null,
        })

        await getChatTurnStatus(
            CHAT_ID,
            REQUEST_ID,
        )

        expect(requestMock).toHaveBeenCalledWith(
            `/api/chats/${CHAT_ID}/turns/by-client-request/${REQUEST_ID}`,
            expect.objectContaining({
                method: 'GET',
            }),
        )
    })

    it('gets an answer passport by durable chat-turn identity', async () => {
        requestMock.mockResolvedValue({
            id: '66666666-6666-4666-8666-666666666666',
            chatTurnId: TURN_ID,
            retrievalRunId: '77777777-7777-4777-8777-777777777777',
            knowledgeBaseId: '88888888-8888-4888-8888-888888888888',
            knowledgeMode: 'KNOWLEDGE_ASSISTED',
            provider: 'mock',
            requestedModel: 'mock-safeai',
            resolvedModel: 'mock-safeai',
            embeddingModel: 'mock-embedding',
            contextSha256: 'a'.repeat(64),
            answerSha256: 'b'.repeat(64),
            evidenceSufficient: true,
            citationsValid: true,
            createdAt: '2026-08-11T10:00:01Z',
            citations: [],
        })

        await getAnswerPassport(CHAT_ID, TURN_ID)

        expect(requestMock).toHaveBeenCalledWith(
            `/api/chats/${CHAT_ID}/turns/${TURN_ID}/answer-passport`,
            expect.objectContaining({ method: 'GET' }),
        )
    })

    it('accepts backend NEW turn state', () => {
        const result = parseChatTurnStatus({
            chatId: CHAT_ID,
            turnId: TURN_ID,
            clientRequestId: REQUEST_ID,
            providerOperationId: OPERATION_ID,
            state: 'NEW',
            provider: null,
            requestedModel: null,
            resolvedModel: null,
            providerRequestId: null,
            providerErrorType: null,
            failureCode: null,
            outcomeAmbiguous: false,
            leaseUntil: null,
            providerCallStartedAt: null,
            createdAt: '2026-08-11T10:00:00Z',
            updatedAt: '2026-08-11T10:00:00Z',
            completedAt: null,
            userMessage: USER_MESSAGE,
            assistantMessage: null,
        })

        expect(result.state).toBe('NEW')
    })

    it('runtime parser still rejects unknown role', () => {
        expect(() =>
            parseChatMessage({
                ...USER_MESSAGE,
                role: 'OWNER',
            }),
        ).toThrow('неизвестное значение')
    })
})
