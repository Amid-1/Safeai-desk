// frontend/src/features/admin/api/retentionApi.test.ts
import { describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../../../shared/api/http'
import { loadRetention, saveRetention } from './retentionApi'

vi.mock('../../../shared/api/http', () => ({apiRequest: vi.fn()}))
const request = vi.mocked(apiRequest)
const gate = {configured: true, workerInitialized: false}
const status = {chatAllowed: true, knowledgeAllowed: false, version: 2,
    chat: gate, document: gate, base: gate, userRetentionSeconds: 604800}

describe('retention API contract', () => {
    it('reads and writes the versioned policy', async () => {
        request.mockResolvedValue(status)
        const signal = new AbortController().signal
        expect(await loadRetention(signal)).toEqual(status)
        expect(request).toHaveBeenLastCalledWith('/api/admin/retention', {signal, method: 'GET'})
        const change = {chatAllowed: false, knowledgeAllowed: false, expectedVersion: 2}
        expect(await saveRetention(change)).toEqual(status)
        expect(request).toHaveBeenLastCalledWith('/api/admin/retention', {method: 'PUT', json: change})
    })
    it.each([{...status, version: -1}, {...status, version: 0.5},
        {...status, version: '2'}, {...status, chatAllowed: 'yes'}, {...status, base: null}])(
        'rejects malformed status %#', async value => {
            request.mockResolvedValue(value)
            await expect(loadRetention()).rejects.toThrow()
        })
})
