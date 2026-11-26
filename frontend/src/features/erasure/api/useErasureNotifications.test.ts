// frontend/src/features/erasure/api/useErasureNotifications.test.ts
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { loadNotifications, type NotificationFeed } from './notificationsApi'
import { useErasureNotifications } from './useErasureNotifications'
vi.mock('./notificationsApi', () => ({ loadNotifications: vi.fn() }))
const load = vi.mocked(loadNotifications)
const feed: NotificationFeed = { canRequest: true, registryCount: 3, reviewCount: null, personal: [], review: [] }
describe('Notification polling scope', () => {
    beforeEach(() => vi.resetAllMocks())
    it('does not fetch anonymously or reuse a prior actor result', async () => {
        load.mockResolvedValueOnce(feed)
        const { result, rerender } = renderHook(({ scope }: { scope: string | null }) => useErasureNotifications(scope, 'route'), { initialProps: { scope: null as string | null } })
        expect(load).not.toHaveBeenCalled()
        rerender({ scope: 'actor-a' })
        await waitFor(() => expect(result.current).toEqual(feed))
        load.mockRejectedValueOnce(new Error('denied'))
        rerender({ scope: 'actor-b' })
        expect(result.current).toBeNull()
        await waitFor(() => expect(load).toHaveBeenCalledTimes(2))
        rerender({ scope: null })
        expect(result.current).toBeNull()
    })
    it('ignores a late response from the previous actor', async () => {
        let resolve: (value: NotificationFeed) => void = () => undefined
        load.mockImplementationOnce(() => new Promise<NotificationFeed>(done => { resolve = done }))
        load.mockResolvedValueOnce({ ...feed, registryCount: 1 })
        const { result, rerender } = renderHook(({ scope }) => useErasureNotifications(scope, 'route'), { initialProps: { scope: 'a' } })
        rerender({ scope: 'b' })
        await waitFor(() => expect(result.current?.registryCount).toBe(1))
        await act(async () => { resolve(feed); await Promise.resolve() })
        expect(result.current?.registryCount).toBe(1)
    })
})
