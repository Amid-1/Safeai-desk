// frontend/src/pages/chatPage.helpers.test.tsx
import {
    describe,
    expect,
    it,
} from 'vitest'
import type {
    ChatMessage,
} from '../api/chatApi'
import {
    buildDisplayMessages,
    createPendingTurn,
    formatPricing,
    formatUsage,
    getModelDisplayName,
    getVisibleMessageContent,
    isSafeToPrepareNewRequest,
    mergeMessages,
    normalizeMessageContent,
} from './chatPage.helpers'

const USER_MESSAGE: ChatMessage = {
    id: '11111111-1111-4111-8111-111111111111',
    clientRequestId:
        'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
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
    createdAt: '2026-08-11T10:00:00Z',
}

function assistant(
    overrides: Partial<ChatMessage> = {},
): ChatMessage {
    return {
        id: '22222222-2222-4222-8222-222222222222',
        clientRequestId: null,
        replyToMessageId: USER_MESSAGE.id,
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
        pricingCalculatedAt: '2026-08-11T10:00:01Z',
        createdAt: '2026-08-11T10:00:01Z',
        ...overrides,
    }
}

describe('chatPage helpers', () => {
    it('normalizes CRLF without trimming content', () => {
        expect(
            normalizeMessageContent(
                '  code\r\nline\r  ',
            ),
        ).toBe('  code\nline\n  ')
    })

    it('removes optimistic USER when server USER with same idempotency key arrives', () => {
        const pending = createPendingTurn(
            '33333333-3333-4333-8333-333333333333',
            'hello',
            'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        )

        const display = buildDisplayMessages(
            [USER_MESSAGE],
            pending,
        )

        expect(display).toHaveLength(1)
        expect(display[0].id).toBe(USER_MESSAGE.id)
    })

    it('merge keeps chronological history and deduplicates ids', () => {
        const early: ChatMessage = {
            ...USER_MESSAGE,
            id: '00000000-0000-4000-8000-000000000010',
            clientRequestId:
                '00000000-0000-4000-8000-000000000011',
            createdAt: '2026-08-10T10:00:00Z',
        }

        const result = mergeMessages(
            [early, USER_MESSAGE],
            [USER_MESSAGE, assistant()],
        )

        expect(result.map((item) => item.id))
            .toEqual([
                early.id,
                USER_MESSAGE.id,
                assistant().id,
            ])
    })

    it('does not treat MISSING usage as zero', () => {
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

    it('does not treat UNPRICED as zero cost', () => {
        expect(
            formatPricing(
                assistant({
                    costUsd: null,
                    pricingStatus: 'UNPRICED',
                }),
            ),
        ).toBe('Стоимость: ещё не рассчитана')
    })

    it('explains the demo model without exposing the mock provider prefix', () => {
        const message = assistant({
            content: 'Mock AI provider response: Ответ пользователю',
        })

        expect(
            getModelDisplayName(message.model),
        ).toBe('Демонстрационная модель SafeAI')
        expect(
            getVisibleMessageContent(message),
        ).toBe('Ответ пользователю')
        expect(formatUsage(message)).toBe(
            'Токены: 10 вход · 5 выход',
        )
        expect(formatPricing(message)).toContain(
            'демо-модель',
        )
    })

    it('never prepares a fresh automatic request from ambiguous outcome', () => {
        expect(
            isSafeToPrepareNewRequest('AMBIGUOUS'),
        ).toBe(false)
        expect(
            isSafeToPrepareNewRequest(
                'IDEMPOTENCY_CONFLICT',
            ),
        ).toBe(false)
    })
})
