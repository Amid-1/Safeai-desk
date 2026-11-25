// frontend/src/pages/ErasureReviewQueuePage.test.tsx
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import ErasureReviewQueuePage from './ErasureReviewQueuePage'
import { loadReviewQueue, type ReviewView } from '../features/erasure/review/reviewApi'

vi.mock('../features/erasure/review/reviewApi', () => ({ loadReviewQueue: vi.fn() }))
const sample: ReviewView = {
    requestId: '4cc9a902-47a2-46f7-9594-49261e6dd90e',
    requesterUserId: '1f45e10d-05fc-45b5-ab1c-f57d4be992fd',
    resourceType: 'CHAT', resourceId: 'acc438fd-d1c8-47a9-82b6-49c0ee50e8b8',
    operation: 'REDACT_CONTENT', scope: 'FULL_CHAT_CONTENT', baseState: 'SUBMITTED',
    reviewState: 'PENDING', version: 0, holdActive: false, reasonCode: null,
    createdAt: '2026-11-25T00:00:00Z', updatedAt: '2026-11-25T00:00:00Z',
    executionAvailable: false, completionProven: false,
}

describe('R2 bounded reviewer queue', () => {
    beforeEach(() => { vi.resetAllMocks() })

    it('filters only server-provided cases by status', async () => {
        vi.mocked(loadReviewQueue).mockResolvedValue([
            sample, { ...sample, requestId: '64c1ea72-773a-4187-a89e-138aa3655290',
                resourceType: 'KNOWLEDGE_DOCUMENT', reviewState: 'REJECTED' },
        ])
        render(<MemoryRouter><ErasureReviewQueuePage /></MemoryRouter>)
        expect(await screen.findAllByRole('link', { name: /Открыть карточку/ })).toHaveLength(2)
        fireEvent.click(screen.getByRole('button', { name: 'Отказы' }))
        expect(screen.getAllByRole('link', { name: /Открыть карточку/ })).toHaveLength(1)
        expect(screen.getByText('Документ')).toBeInTheDocument()
        expect(screen.queryByText('100%')).not.toBeInTheDocument()
    })

    it('keeps the role boundary visible when the endpoint refuses a request', async () => {
        vi.mocked(loadReviewQueue).mockRejectedValue(new Error('Forbidden'))
        render(<MemoryRouter><ErasureReviewQueuePage /></MemoryRouter>)
        expect(await screen.findByRole('alert')).toHaveTextContent('Очередь недоступна')
        expect(screen.getByText(/само по себе не выдаёт полномочия/)).toBeInTheDocument()
    })
})
