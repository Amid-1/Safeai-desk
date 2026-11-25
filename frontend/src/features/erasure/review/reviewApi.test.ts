// frontend/src/features/erasure/review/reviewApi.test.ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../../../shared/api/http'
import { loadReviewQueue, loadReviewSummary, reviewDecision, type ReviewView } from './reviewApi'

vi.mock('../../../shared/api/http', () => ({ apiRequest: vi.fn() }))
const fakeRequest = vi.mocked(apiRequest)
const REVIEW: ReviewView = {
    requestId: '22100a42-472f-4a51-87bf-4a571e342ae0',
    requesterUserId: '53f7d229-b725-437d-b138-c189ffca72b1',
    resourceType: 'CHAT', resourceId: '33a6883f-8bbd-47e7-9c47-a6a9e8a2f9d0',
    operation: 'REDACT_CONTENT', scope: 'FULL_CHAT_CONTENT', baseState: 'SUBMITTED',
    reviewState: 'PENDING', version: 1, holdActive: false, reasonCode: null,
    createdAt: '2026-11-25T00:00:00Z', updatedAt: '2026-11-25T00:00:00Z',
    executionAvailable: false, completionProven: false,
}

describe('R1b non-destructive review API', () => {
    beforeEach(() => { vi.resetAllMocks() })
    it('loads a bounded server-scoped queue', async () => {
        fakeRequest.mockResolvedValueOnce({ items: [REVIEW] })
        const items = await loadReviewQueue()
        expect(items).toHaveLength(1)
        expect(fakeRequest).toHaveBeenCalledWith('/api/admin/erasure/reviews',
            { method: 'GET', signal: undefined })
    })
    it('does not accept a forged completion claim', async () => {
        fakeRequest.mockResolvedValueOnce({ items: [{ ...REVIEW, completionProven: true }] })
        await expect(loadReviewQueue()).rejects.toThrow(/подтвердил/)
    })
    it('sends version and idempotency headers for decisions', async () => {
        fakeRequest.mockResolvedValueOnce(REVIEW)
        await reviewDecision(REVIEW, 'NEEDS_INFORMATION', 'INSUFFICIENT_INFORMATION')
        const [, opts] = fakeRequest.mock.calls[0]
        expect(opts?.method).toBe('POST')
        expect(opts?.headers).toEqual(expect.objectContaining({ 'If-Match': '"1"',
            'Idempotency-Key': expect.any(String) }))
    })
    it('rejects fake overview counts', async () => {
        fakeRequest.mockResolvedValueOnce({ total: -1, pending: 0, held: 0, needsInformation: 0 })
        await expect(loadReviewSummary()).rejects.toThrow('Некорректная версия')
    })
})
