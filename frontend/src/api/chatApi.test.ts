import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    getChatById,
    getChatTurnStatus,
    parseChatMessage,
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

const requestMock =
    vi.mocked(apiRequest)

const CHAT_RESPONSE = {
    id: '33333333-3333-3333-3333-333333333333',
    title: 'Chat',
    createdAt:
        '2026-08-04T10:00:00Z',
    updatedAt:
        '2026-08-04T10:00:01Z',
    messages: [],
}

describe('chatApi', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('передаёт clientRequestId и chat timeout', async () => {
        requestMock.mockResolvedValue(
            CHAT_RESPONSE,
        )

        await sendMessage(
            CHAT_RESPONSE.id,
            {
                content: 'hello',
                clientRequestId:
                    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            },
        )

        expect(requestMock)
            .toHaveBeenCalledWith(
                `/api/chats/${CHAT_RESPONSE.id}/messages`,
                expect.objectContaining({
                    method: 'POST',
                    json: {
                        content: 'hello',
                        clientRequestId:
                            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
                    },
                    timeoutMs: 95_000,
                }),
            )
    })

    it('отклоняет невалидный chat UUID до fetch', async () => {
        await expect(
            getChatById('../chat'),
        ).rejects.toThrow(
            'Некорректный UUID',
        )

        expect(requestMock)
            .not.toHaveBeenCalled()
    })

    it('запрашивает turn по clientRequestId', async () => {
        requestMock.mockResolvedValue({
            chatId: CHAT_RESPONSE.id,
            clientRequestId:
                'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            state: 'PROCESSING',
            userMessageId: null,
            assistantMessageId: null,
            errorCode: null,
            errorMessage: null,
            createdAt:
                '2026-08-04T10:00:00Z',
            updatedAt:
                '2026-08-04T10:00:01Z',
        })

        await getChatTurnStatus(
            CHAT_RESPONSE.id,
            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        )

        expect(requestMock)
            .toHaveBeenCalledWith(
                `/api/chats/${CHAT_RESPONSE.id}/turns/by-client-request/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa`,
                expect.objectContaining({
                    method: 'GET',
                }),
            )
    })

    it('runtime parser отклоняет неизвестный role', () => {
        expect(() =>
            parseChatMessage({
                id: '11111111-1111-1111-1111-111111111111',
                clientRequestId: null,
                replyToMessageId: null,
                role: 'OWNER',
                content: 'x',
                status: 'COMPLETED',
                inputTokens: null,
                outputTokens: null,
                costUsd: null,
                createdAt:
                    '2026-08-04T10:00:00Z',
            }),
        ).toThrow(
            'неизвестное значение',
        )
    })
})
