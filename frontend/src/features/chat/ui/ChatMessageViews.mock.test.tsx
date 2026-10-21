// frontend/src/features/chat/ui/ChatMessageViews.mock.test.tsx
/* frontend/src/features/chat/ui/ChatMessageViews.mock.test.tsx */
import {
    render,
    screen,
} from '@testing-library/react'
import {
    describe,
    expect,
    it,
} from 'vitest'
import type {
    DisplayMessage,
} from '../lib/chatPage.helpers'
import { MessageView } from './ChatMessageViews'

const completed: DisplayMessage = {
    id: '11111111-1111-4111-8111-111111111111',
    clientRequestId: '22222222-2222-4222-8222-222222222222',
    replyToMessageId: null,
    role: 'ASSISTANT',
    content: 'Ответ модели',
    status: 'COMPLETED',
    requestedModel: 'mock-safeai',
    model: 'mock-safeai',
    providerMessageId: null,
    providerRequestId: null,
    aiResponseStatus: 'COMPLETED',
    finishReason: null,
    inputTokens: 5,
    outputTokens: 10,
    usageStatus: 'AVAILABLE',
    costUsd: '0',
    pricingStatus: 'FREE',
    currency: 'USD',
    pricingVersion: null,
    pricingCalculatedAt: null,
    createdAt: '2026-10-17T12:00:00.000Z',
}

describe('mock-provider response badge', () => {
    it('labels the known mock model explicitly', () => {
        render(<MessageView message={completed} />)
        expect(screen.getByText('Тестовый ответ · mock')).toBeInTheDocument()
    })

    it('does not label a non-mock response as a test response', () => {
        render(<MessageView message={{ ...completed, model: 'gpt-example' }} />)
        expect(screen.queryByText('Тестовый ответ · mock')).not.toBeInTheDocument()
    })
})
