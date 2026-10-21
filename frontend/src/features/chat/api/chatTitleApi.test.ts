// frontend/src/features/chat/api/chatTitleApi.test.ts
import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    updateChatTitle,
} from './chatApi'
import {
    apiRequest,
} from '../../../shared/api/http'

vi.mock('../../../shared/api/http', async (importOriginal) => {
    const actual =
        await importOriginal<typeof import('../../../shared/api/http')>()

    return {
        ...actual,
        apiRequest: vi.fn(),
    }
})

const requestMock =
    vi.mocked(apiRequest)

const CHAT_ID =
    '11111111-1111-4111-8111-111111111111'

describe('chat title API', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('PATCHes the owned chat title and validates the returned Chat DTO', async () => {
        requestMock.mockResolvedValue({
            id: CHAT_ID,
            title: 'Архитектура SafeAI',
            createdAt:
                '2026-09-27T12:00:00Z',
            updatedAt:
                '2026-09-27T12:01:00Z',
        })

        const result =
            await updateChatTitle(
                CHAT_ID,
                'Архитектура SafeAI',
            )

        expect(result.title)
            .toBe(
                'Архитектура SafeAI',
            )

        expect(requestMock)
            .toHaveBeenCalledWith(
                `/api/chats/${CHAT_ID}`,
                expect.objectContaining({
                    method: 'PATCH',
                    json: {
                        title:
                            'Архитектура SafeAI',
                    },
                }),
            )
    })
})
