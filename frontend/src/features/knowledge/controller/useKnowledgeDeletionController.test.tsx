import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
    acceptDocumentDeletion, deletionCapability, listDocumentDeletions,
} from '../../../entities/knowledge/api/knowledgeDocumentDeletionApi'
import { useKnowledgeDeletionController } from './useKnowledgeDeletionController'

vi.mock('../../../entities/knowledge/api/knowledgeDocumentDeletionApi', () => ({
    acceptDocumentDeletion: vi.fn(),
    deletionCapability: vi.fn(),
    listDocumentDeletions: vi.fn(),
}))

const BASE = '11111111-1111-4111-8111-111111111111'
const OP = {
    operationId: '22222222-2222-4222-8222-222222222222',
    documentId: '33333333-3333-4333-8333-333333333333',
    state: 'BLOCKED_BY_RETENTION' as const,
    createdAt: '2026-10-29T12:00:00Z', completedAt: null,
}
const capability = vi.mocked(deletionCapability)
const history = vi.mocked(listDocumentDeletions)
const accept = vi.mocked(acceptDocumentDeletion)
const config = (isOwner = true, knowledgeBaseId = BASE) => ({
    knowledgeBaseId, isOwner, setError: vi.fn(), refresh: vi.fn().mockResolvedValue(undefined),
})

beforeEach(() => {
    vi.clearAllMocks()
    capability.mockResolvedValue(false)
    history.mockResolvedValue([OP])
})

describe('knowledge deletion: read-only history independent of DELETE flags', () => {
    it('shows immutable retention history when destructive capability is OFF', async () => {
        const { result } = renderHook(() => useKnowledgeDeletionController(config()))
        await waitFor(() => expect(result.current.operations).toEqual([OP]))
        expect(result.current.available).toBe(false)
        expect(result.current.historyError).toBe(false)
        expect(history).toHaveBeenCalledWith(BASE, expect.any(AbortSignal))
        expect(accept).not.toHaveBeenCalled()
    })

    it('keeps read-only evidence even if capability check throws', async () => {
        capability.mockRejectedValue(new Error('capability failed'))
        const { result } = renderHook(() => useKnowledgeDeletionController(config()))
        await waitFor(() => expect(result.current.operations).toEqual([OP]))
        expect(result.current.available).toBe(false)
        expect(result.current.historyError).toBe(false)
    })

    it('never enables DELETE if reading history fails', async () => {
        capability.mockResolvedValue(true)
        history.mockRejectedValue(new Error('read unavailable'))
        const { result } = renderHook(() => useKnowledgeDeletionController(config()))
        await waitFor(() => expect(result.current.historyError).toBe(true))
        expect(result.current.available).toBe(false)
        expect(result.current.operations).toEqual([])
    })

    it('ignores a late GET response from a previous base', async () => {
        let finishOld!: (value: Awaited<ReturnType<typeof listDocumentDeletions>>) => void
        history.mockImplementationOnce(() => new Promise<Awaited<ReturnType<typeof listDocumentDeletions>>>(
            resolve => { finishOld = resolve },
        ))
        history.mockResolvedValueOnce([])
        const otherBase = '44444444-4444-4444-8444-444444444444'
        const { result, rerender } = renderHook(
            ({ base }) => useKnowledgeDeletionController(config(true, base)),
            { initialProps: { base: BASE } },
        )
        await waitFor(() => expect(history).toHaveBeenCalledTimes(1))
        rerender({ base: otherBase })
        await waitFor(() => expect(history).toHaveBeenCalledTimes(2))
        await act(async () => { finishOld([OP]) })
        await waitFor(() => expect(result.current.operations).toEqual([]))
    })

    it('clears sensitive history when ownership is revoked', async () => {
        const { result, rerender } = renderHook(
            ({ isOwner }) => useKnowledgeDeletionController(config(isOwner)),
            { initialProps: { isOwner: true } },
        )
        await waitFor(() => expect(result.current.operations).toEqual([OP]))
        rerender({ isOwner: false })
        await waitFor(() => expect(result.current.operations).toEqual([]))
        expect(result.current.available).toBe(false)
    })

    it('polls GET despite disabled capability, without polling DELETE capability', async () => {
        vi.useFakeTimers()
        try {
            const { result, unmount } = renderHook(() => useKnowledgeDeletionController(config()))
            await act(async () => { await Promise.resolve(); await Promise.resolve() })
            expect(result.current.operations).toEqual([OP])
            await act(async () => { await vi.advanceTimersByTimeAsync(12_000) })
            expect(history).toHaveBeenCalledTimes(2)
            expect(capability).toHaveBeenCalledTimes(1)
            unmount()
        } finally {
            vi.useRealTimers()
        }
    })
})
