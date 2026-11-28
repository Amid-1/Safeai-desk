// frontend/src/features/erasure/api/notificationsApi.test.ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../../../shared/api/http'
import { loadNotifications } from './notificationsApi'
vi.mock('../../../shared/api/http', () => ({ apiRequest: vi.fn() }))
const request = vi.mocked(apiRequest)
const item = { eventId: 'a8517423-018f-412b-95a2-9c79748bf19a', requestId: '83155da1-4fae-4629-abde-9e09aa500b13',
    eventCode: 'APPROVED_REVIEW_ONLY', createdAt: '2026-11-26T00:00:00Z', audience: 'PERSONAL' }
const feed = { canRequest: true, registryCount: null, reviewCount: null, personal: [item], review: [] }
describe('Notification API', () => {
    beforeEach(() => vi.resetAllMocks())
    it('parses personal events and preserves absent counters', async () => {
        request.mockResolvedValueOnce(feed)
        expect(await loadNotifications()).toEqual(feed)
        expect(request).toHaveBeenCalledWith('/api/erasure/requests/notifications', { method: 'GET', signal: undefined })
    })
    it('rejects events for a disabled audience and invalid counters', async () => {
        for (const value of [{ ...feed, canRequest: false }, { ...feed, review: [item] }, { ...feed, registryCount: -1 },
            { ...feed, personal: [{ ...item, requestId: '../admin' }] }]) {
            request.mockResolvedValueOnce(value)
            await expect(loadNotifications()).rejects.toThrow()
        }
    })
})
