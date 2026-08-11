import {
    afterEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    ApiError,
    apiRequest,
} from './http'

describe('http chat error envelope compatibility', () => {
    afterEach(() => {
        vi.unstubAllGlobals()
    })

    it('maps backend ChatErrorResponse.code to ApiError.errorCode', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(
                new Response(
                    JSON.stringify({
                        timestamp:
                            '2026-08-11T10:00:00Z',
                        status: 409,
                        code: 'AI_OUTCOME_AMBIGUOUS',
                        message: 'ambiguous',
                        chatId:
                            '33333333-3333-4333-8333-333333333333',
                        turnId:
                            '44444444-4444-4444-8444-444444444444',
                        clientRequestId:
                            'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
                        retryAfterSeconds: null,
                    }),
                    {
                        status: 409,
                        headers: {
                            'Content-Type':
                                'application/json',
                        },
                    },
                ),
            ),
        )

        await expect(
            apiRequest('/api/test', {
                method: 'GET',
            }),
        ).rejects.toMatchObject({
            name: 'ApiError',
            status: 409,
            errorCode: 'AI_OUTCOME_AMBIGUOUS',
        } satisfies Partial<ApiError>)
    })
})
