// frontend/src/api/chatCreateApi.test.ts
import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    createChat,
} from './chatApi'
import {
    apiRequest,
} from './http'

vi.mock('./http', async (importOriginal) => {
    const actual =
        await importOriginal<typeof import('./http')>()

    return {
        ...actual,
        apiRequest: vi.fn(),
    }
})

const requestMock =
    vi.mocked(apiRequest)

const CHAT_ID =
    '11111111-1111-4111-8111-111111111111'

describe('chat creation API', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('creates a default chat without claiming an explicit USER title', async () => {
        requestMock.mockResolvedValue({
            id: CHAT_ID,
            title: 'Новый чат',
            createdAt:
                '2026-09-27T18:00:00Z',
            updatedAt:
                '2026-09-27T18:00:00Z',
        })

        const result =
            await createChat()

        expect(result.title)
            .toBe('Новый чат')

        expect(requestMock)
            .toHaveBeenCalledWith(
                '/api/chats',
                expect.objectContaining({
                    method: 'POST',
                    json: {
                        title: null,
                    },
                }),
            )
    })

    it('still supports an explicit creation title when a caller intentionally supplies one', async () => {
        requestMock.mockResolvedValue({
            id: CHAT_ID,
            title: 'Проект SafeAI',
            createdAt:
                '2026-09-27T18:00:00Z',
            updatedAt:
                '2026-09-27T18:00:00Z',
        })

        await createChat(
            'Проект SafeAI',
        )

        expect(requestMock)
            .toHaveBeenCalledWith(
                '/api/chats',
                expect.objectContaining({
                    method: 'POST',
                    json: {
                        title:
                            'Проект SafeAI',
                    },
                }),
            )
    })
})
