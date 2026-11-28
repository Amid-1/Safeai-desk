// frontend/src/features/erasure/api/erasureApi.test.ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../../../shared/api/http'
import {
    loadMine, loadTenantQueue, previewCase, createDraft,
    submitDraft, withdrawCase, type CaseView, type Target,
} from './erasureApi'

vi.mock('../../../shared/api/http', () => ({ apiRequest: vi.fn() }))
const request = vi.mocked(apiRequest)
const target: Target = {
    resourceType: 'CHAT', resourceId: 'e05dfdf7-ea8f-4a28-89fb-8c936cf9f599',
    operation: 'REDACT_CONTENT', scope: 'FULL_CHAT_CONTENT',
}
const item: CaseView = {
    ...target, requestId: '96a1128b-51ca-42c2-90ca-cf2dd7b0de78',
    state: 'DRAFT', version: 0, createdAt: '2026-11-24T12:00:00Z',
    executionStarted: false, completionProven: false,
}
const preview = {
    previewId: '3aba5f9c-94d6-4b1a-87e5-c82b135c65e1',
    previewDigest: 'a'.repeat(64), expectedResourceVersion: 7,
    expiresAt: '2026-11-24T12:15:00Z', executionAvailable: false as const,
}

describe('Erasure R1a non-destructive API', () => {
    beforeEach(() => request.mockReset())

    it('lists only own cases using a scoped API', async () => {
        request.mockResolvedValue({ items: [item] })
        const ac = new AbortController()
        expect(await loadMine(ac.signal)).toEqual([item])
        expect(request).toHaveBeenCalledWith('/api/erasure/requests',
            { method: 'GET', signal: ac.signal })
    })

    it('lists tenant queue from separate administrator endpoint', async () => {
        request.mockResolvedValue({ items: [item] })
        expect(await loadTenantQueue()).toEqual([item])
        expect(request).toHaveBeenCalledWith('/api/admin/erasure/queue',
            { method: 'GET', signal: undefined })
    })

    it('checks preview but never requests deletion', async () => {
        request.mockResolvedValue(preview)
        expect(await previewCase(target)).toEqual(preview)
        expect(request).toHaveBeenCalledWith('/api/erasure/previews',
            { method: 'POST', json: target })
    })

    it('creates a DRAFT with a random idempotency key', async () => {
        request.mockResolvedValue(item)
        expect((await createDraft(target, preview, 'OWNER_REQUEST')).state).toBe('DRAFT')
        const [url, opts] = request.mock.calls[0]
        expect(url).toBe('/api/erasure/requests')
        expect(opts?.method).toBe('POST')
        expect(opts?.headers).toEqual({ 'Idempotency-Key': expect.any(String) })
        expect(opts?.json).toMatchObject({ target, previewId: preview.previewId })
    })

    it('submits only a draft, with an exact version and idempotency key', async () => {
        request.mockResolvedValue({ ...item, state: 'SUBMITTED', version: 1 })
        expect((await submitDraft(item)).state).toBe('SUBMITTED')
        expect(request.mock.calls[0][1]?.headers).toMatchObject({
            'If-Match': '"0"', 'Idempotency-Key': expect.any(String),
        })
    })

    it('withdraw uses same strict version contract', async () => {
        request.mockResolvedValue({ ...item, state: 'WITHDRAWN', version: 1 })
        expect((await withdrawCase(item)).state).toBe('WITHDRAWN')
        expect(request.mock.calls[0][1]?.headers).toMatchObject({
            'If-Match': '"0"', 'Idempotency-Key': expect.any(String),
        })
    })

    it.each([
        { ...item, executionStarted: true },
        { ...item, completionProven: true },
        { ...item, state: 'COMPLETED_FULL_SCOPE' },
        { ...item, version: -1 },
    ])('refuses false completion or unsupported state %#', async invalid => {
        request.mockResolvedValue({ items: [invalid] })
        await expect(loadMine()).rejects.toThrow()
    })

    it('rejects oversized or malformed pages', async () => {
        request.mockResolvedValue({ items: new Array(101).fill(item) })
        await expect(loadMine()).rejects.toThrow()
        request.mockResolvedValue({ items: 'not-an-array' })
        await expect(loadMine()).rejects.toThrow()
    })

    it('rejects preview claiming an enabled executor', async () => {
        request.mockResolvedValue({ ...preview, executionAvailable: true })
        await expect(previewCase(target)).rejects.toThrow()
    })
})
