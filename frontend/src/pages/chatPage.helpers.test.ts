import {
    describe,
    expect,
    it,
} from 'vitest'
import type {
    ChatDetails,
    ChatMessage,
} from '../api/chatApi'
import {
    buildDisplayMessages,
    createPendingTurn,
    formatPricing,
    formatUsage,
    mergeChatDetails,
    mergeMessages,
    normalizeMessageContent,
} from './chatPage.helpers'

const USER_MESSAGE: ChatMessage = {
    id: '11111111-1111-1111-1111-111111111111',
    clientRequestId:
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    replyToMessageId: null,
    role: 'USER',
    content: 'hello',
    status: 'COMPLETED',
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
    createdAt:
        '2026-08-04T10:00:00Z',
}

function assistant(
    overrides: Partial<ChatMessage> = {},
): ChatMessage {
    return {
        id: '22222222-2222-2222-2222-222222222222',
        clientRequestId: null,
        replyToMessageId:
            USER_MESSAGE.id,
        role: 'ASSISTANT',
        content: 'answer',
        status: 'COMPLETED',
        requestedModel: 'mock-safeai',
        model: 'mock-safeai',
        providerMessageId: null,
        providerRequestId: null,
        aiResponseStatus: 'COMPLETED',
        finishReason: 'stop',
        inputTokens: 10,
        outputTokens: 5,
        usageStatus: 'AVAILABLE',
        costUsd: '0',
        pricingStatus: 'FREE',
        currency: 'USD',
        pricingVersion: 'mock',
        pricingCalculatedAt:
            '2026-08-04T10:00:01Z',
        createdAt:
            '2026-08-04T10:00:01Z',
        ...overrides,
    }
}

describe('chatPage helpers', () => {
    it('нормализует CRLF без trim содержимого', () => {
        expect(
            normalizeMessageContent(
                '  code\r\nline\r  ',
            ),
        ).toBe('  code\nline\n  ')
    })

    it('optimistic message использует стабильный clientRequestId', () => {
        const pending =
            createPendingTurn(
                '33333333-3333-3333-3333-333333333333',
                'hello',
                'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            )

        const display =
            buildDisplayMessages(
                [],
                pending,
            )

        expect(display).toHaveLength(1)
        expect(display[0]).toMatchObject({
            id:
                'pending-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            clientRequestId:
                'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            uiStatus: 'SENDING',
        })
    })

    it('server USER удаляет optimistic запись по clientRequestId', () => {
        const pending =
            createPendingTurn(
                '33333333-3333-3333-3333-333333333333',
                'hello',
                'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            )

        const display =
            buildDisplayMessages(
                [USER_MESSAGE],
                pending,
            )

        expect(display).toHaveLength(1)
        expect(display[0].id).toBe(
            USER_MESSAGE.id,
        )
    })

    it('merge сохраняет раннюю историю и удаляет дубликаты', () => {
        const early: ChatMessage = {
            ...USER_MESSAGE,
            id: '00000000-0000-0000-0000-000000000010',
            clientRequestId:
                '00000000-0000-0000-0000-000000000011',
            createdAt:
                '2026-08-03T10:00:00Z',
        }

        const result = mergeMessages(
            [
                early,
                USER_MESSAGE,
            ],
            [
                USER_MESSAGE,
                assistant(),
            ],
        )

        expect(result.map((item) => item.id))
            .toEqual([
                early.id,
                USER_MESSAGE.id,
                assistant().id,
            ])
    })

    it('mergeChatDetails не теряет ранее загруженные сообщения', () => {
        const current: ChatDetails = {
            id: '33333333-3333-3333-3333-333333333333',
            title: 'Chat',
            createdAt:
                '2026-08-03T10:00:00Z',
            updatedAt:
                '2026-08-04T10:00:00Z',
            messages: [
                {
                    ...USER_MESSAGE,
                    id: '00000000-0000-0000-0000-000000000010',
                    clientRequestId:
                        '00000000-0000-0000-0000-000000000011',
                    createdAt:
                        '2026-08-03T10:00:00Z',
                },
            ],
        }

        const incoming: ChatDetails = {
            ...current,
            messages: [
                USER_MESSAGE,
                assistant(),
            ],
        }

        expect(
            mergeChatDetails(
                current,
                incoming,
            ).messages,
        ).toHaveLength(3)
    })

    it('MISSING usage не отображается как ноль', () => {
        expect(
            formatUsage(
                assistant({
                    inputTokens: null,
                    outputTokens: null,
                    usageStatus: 'MISSING',
                }),
            ),
        ).toContain('отсутствуют')
    })

    it('PARTIAL usage отмечается как неполный', () => {
        expect(
            formatUsage(
                assistant({
                    inputTokens: 10,
                    outputTokens: null,
                    usageStatus: 'PARTIAL',
                }),
            ),
        ).toContain('неполные')
    })

    it('FREE подтверждает нулевую стоимость', () => {
        expect(
            formatPricing(
                assistant(),
            ),
        ).toContain(
            'подтверждённо бесплатно',
        )
    })

    it('UNPRICED не отображается как $0', () => {
        expect(
            formatPricing(
                assistant({
                    costUsd: null,
                    pricingStatus: 'UNPRICED',
                }),
            ),
        ).toBe(
            'стоимость: не рассчитана',
        )
    })

    it('CALCULATION_FAILED показывает ошибку', () => {
        expect(
            formatPricing(
                assistant({
                    costUsd: null,
                    pricingStatus:
                        'CALCULATION_FAILED',
                }),
            ),
        ).toBe(
            'стоимость: ошибка расчёта',
        )
    })
})
