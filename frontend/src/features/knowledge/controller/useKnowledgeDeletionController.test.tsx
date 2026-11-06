// frontend/src/features/knowledge/controller/useKnowledgeDeletionController.test.tsx
import {act, renderHook, waitFor} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'

import {
    acceptDocumentDeletion,
    deletionCapability,
    listDocumentDeletions,
} from '../../../entities/knowledge/api/knowledgeDocumentDeletionApi'
import type {KnowledgeDocument} from '../../../entities/knowledge/api/knowledgeDocumentApi'
import type {KnowledgeDocumentDeletion} from '../../../entities/knowledge/api/knowledgeDocumentDeletionApi'
import {
    clearKnowledgeDeletionRequestKey,
    getKnowledgeDeletionRequestKey,
} from '../lib/knowledgeDeletionRequestKey'
import {deferred, flushMicrotasks} from '../test/knowledgeDeletionTestUtils'
import {useKnowledgeDeletionController} from './useKnowledgeDeletionController'

vi.mock('../../../entities/knowledge/api/knowledgeDocumentDeletionApi', () => ({
    acceptDocumentDeletion: vi.fn(),
    deletionCapability: vi.fn(),
    listDocumentDeletions: vi.fn(),
}))

vi.mock('../lib/knowledgeDeletionRequestKey', () => ({
    getKnowledgeDeletionRequestKey: vi.fn(),
    clearKnowledgeDeletionRequestKey: vi.fn(),
}))

const BASE = '11111111-1111-4111-8111-111111111111'
const OTHER_BASE = '44444444-4444-4444-8444-444444444444'

const OP: KnowledgeDocumentDeletion = {
    operationId: '22222222-2222-4222-8222-222222222222',
    documentId: '33333333-3333-4333-8333-333333333333',
    state: 'BLOCKED_BY_RETENTION',
    createdAt: '2026-10-29T12:00:00Z',
    completedAt: null,
}

const ACCEPTED: KnowledgeDocumentDeletion = {
    ...OP,
    state: 'DELETING',
}

const DOCUMENT: KnowledgeDocument = {
    id: OP.documentId,
    knowledgeBaseId: BASE,
    name: 'Документ для удаления',
    version: 7,
    enabled: true,
    currentVersionId: null,
    searchableVersionId: null,
    versionNumber: null,
    originalFilename: null,
    mediaType: null,
    sizeBytes: 0,
    status: 'READY',
    createdAt: '2026-10-29T12:00:00Z',
    updatedAt: '2026-10-29T12:00:00Z',
}

const capability = vi.mocked(deletionCapability)
const history = vi.mocked(listDocumentDeletions)
const accept = vi.mocked(acceptDocumentDeletion)
const getKey = vi.mocked(getKnowledgeDeletionRequestKey)
const clearKey = vi.mocked(clearKnowledgeDeletionRequestKey)

function makeConfig(
    isOwner = true,
    knowledgeBaseId = BASE,
    overrides: {
        setError?: (message: string) => void
        refresh?: () => Promise<unknown>
    } = {},
) {
    return {
        knowledgeBaseId,
        isOwner,
        setError: overrides.setError ?? vi.fn(),
        refresh: overrides.refresh ?? vi.fn().mockResolvedValue(undefined),
    }
}

async function renderAndConfirmDocumentDeletion() {
    const hook = renderHook(() => useKnowledgeDeletionController(makeConfig()))

    await waitFor(() => expect(hook.result.current.available).toBe(true))
    act(() => hook.result.current.requestDeletion(DOCUMENT))

    await act(async () => {
        await hook.result.current.confirmDeletion()
    })

    return hook
}

beforeEach(() => {
    vi.clearAllMocks()

    capability.mockReset().mockResolvedValue(false)
    history.mockReset().mockResolvedValue([OP])
    accept.mockReset()

    getKey.mockReset().mockReturnValue('document-idempotency-key')
    clearKey.mockReset()
})

describe('useKnowledgeDeletionController: read-only history and access control', () => {
    it('keeps read-only history visible when destructive capability is disabled', async () => {
        const {result} = renderHook(() => useKnowledgeDeletionController(makeConfig()))

        await waitFor(() => expect(result.current.operations).toEqual([OP]))

        expect(result.current.available).toBe(false)
        expect(result.current.historyError).toBe(false)
        expect(history).toHaveBeenCalledWith(BASE, expect.any(AbortSignal))
        expect(accept).not.toHaveBeenCalled()
    })

    it('keeps history evidence when capability lookup fails', async () => {
        capability.mockRejectedValue(new Error('capability unavailable'))

        const {result} = renderHook(() => useKnowledgeDeletionController(makeConfig()))

        await waitFor(() => expect(result.current.operations).toEqual([OP]))

        expect(result.current.available).toBe(false)
        expect(result.current.historyError).toBe(false)
        expect(result.current.unavailableReason).toMatch(/Не удалось проверить доступность/)
    })

    it('fails closed when history cannot be read, even if capability is enabled', async () => {
        capability.mockResolvedValue(true)
        history.mockRejectedValue(new Error('history unavailable'))

        const {result} = renderHook(() => useKnowledgeDeletionController(makeConfig()))

        await waitFor(() => expect(result.current.historyError).toBe(true))

        expect(result.current.available).toBe(false)
        expect(result.current.operations).toEqual([])
        expect(result.current.unavailableReason).toMatch(/Новый запрос запрещён/)
    })

    it('ignores a late history response from a previous knowledge base', async () => {
        capability.mockResolvedValue(true)

        const oldHistory = deferred<Awaited<ReturnType<typeof listDocumentDeletions>>>()
        history
            .mockImplementationOnce(() => oldHistory.promise)
            .mockResolvedValueOnce([])

        const {result, rerender} = renderHook(
            ({baseId}) => useKnowledgeDeletionController(makeConfig(true, baseId)),
            {initialProps: {baseId: BASE}},
        )

        await waitFor(() => expect(history).toHaveBeenCalledTimes(1))

        rerender({baseId: OTHER_BASE})

        await waitFor(() => expect(result.current.available).toBe(true))
        expect(result.current.operations).toEqual([])

        await act(async () => {
            oldHistory.resolve([OP])
            await oldHistory.promise
        })

        expect(result.current.operations).toEqual([])
        expect(result.current.available).toBe(true)
    })

    it('clears sensitive history immediately after ownership is revoked', async () => {
        const {result, rerender} = renderHook(
            ({owner}) => useKnowledgeDeletionController(makeConfig(owner)),
            {initialProps: {owner: true}},
        )

        await waitFor(() => expect(result.current.operations).toEqual([OP]))

        rerender({owner: false})

        await waitFor(() => expect(result.current.operations).toEqual([]))

        expect(result.current.available).toBe(false)
        expect(result.current.canShowAction).toBe(false)
    })

    it('does not apply a pending history response after ownership is revoked', async () => {
        const pendingHistory = deferred<Awaited<ReturnType<typeof listDocumentDeletions>>>()
        history.mockImplementationOnce(() => pendingHistory.promise)

        const {result, rerender} = renderHook(
            ({owner}) => useKnowledgeDeletionController(makeConfig(owner)),
            {initialProps: {owner: true}},
        )

        await waitFor(() => expect(history).toHaveBeenCalledTimes(1))

        rerender({owner: false})

        await act(async () => {
            pendingHistory.resolve([OP])
            await pendingHistory.promise
        })

        expect(result.current.operations).toEqual([])
        expect(result.current.available).toBe(false)
    })

    it('polls read-only history even while destructive capability is disabled', async () => {
        vi.useFakeTimers()

        try {
            const {result, unmount} = renderHook(
                () => useKnowledgeDeletionController(makeConfig()),
            )

            await flushMicrotasks()

            expect(result.current.operations).toEqual([OP])

            await act(async () => {
                await vi.advanceTimersByTimeAsync(12_000)
            })

            expect(history).toHaveBeenCalledTimes(2)
            expect(capability).toHaveBeenCalledTimes(1)
            expect(accept).not.toHaveBeenCalled()

            unmount()
        } finally {
            vi.useRealTimers()
        }
    })

    it('fails closed after a polling error and does not silently re-enable DELETE', async () => {
        vi.useFakeTimers()

        capability.mockResolvedValue(true)
        history
            .mockResolvedValueOnce([])
            .mockRejectedValueOnce(new Error('poll failed'))
            .mockResolvedValueOnce([])

        try {
            const {result, unmount} = renderHook(
                () => useKnowledgeDeletionController(makeConfig()),
            )

            await flushMicrotasks()
            expect(result.current.available).toBe(true)

            await act(async () => {
                await vi.advanceTimersByTimeAsync(12_000)
            })

            expect(result.current.available).toBe(false)
            expect(result.current.historyError).toBe(true)

            await act(async () => {
                await vi.advanceTimersByTimeAsync(12_000)
            })

            expect(result.current.historyError).toBe(false)
            expect(result.current.available).toBe(false)
            expect(capability).toHaveBeenCalledTimes(1)

            unmount()
        } finally {
            vi.useRealTimers()
        }
    })
})

describe('useKnowledgeDeletionController: intent, idempotency and mutation safety', () => {
    it('does not create a second DELETE when an operation already exists', async () => {
        capability.mockResolvedValue(true)

        const {result} = renderHook(
            () => useKnowledgeDeletionController(makeConfig()),
        )

        await waitFor(() => expect(result.current.operations).toEqual([OP]))

        act(() => result.current.requestDeletion(DOCUMENT))

        expect(result.current.target).toBeNull()
        expect(result.current.visibleOperation).toEqual(OP)
        expect(accept).not.toHaveBeenCalled()
    })

    it('rejects a document that belongs to another base', async () => {
        capability.mockResolvedValue(true)

        const setError = vi.fn()
        const {result} = renderHook(
            () => useKnowledgeDeletionController(makeConfig(true, BASE, {setError})),
        )

        await waitFor(() => expect(result.current.available).toBe(true))

        act(() => {
            result.current.requestDeletion({
                ...DOCUMENT,
                knowledgeBaseId: OTHER_BASE,
            })
        })

        expect(result.current.target).toBeNull()
        expect(setError).toHaveBeenCalledWith(
            'Документ не принадлежит выбранной базе. Обновите список документов.',
        )
        expect(accept).not.toHaveBeenCalled()
    })

    it('rejects an operation opened for a different document', async () => {
        capability.mockResolvedValue(true)
        history.mockResolvedValue([])

        const setError = vi.fn()
        const {result} = renderHook(
            () => useKnowledgeDeletionController(makeConfig(true, BASE, {setError})),
        )

        await waitFor(() => expect(result.current.available).toBe(true))

        act(() => {
            result.current.openOperation(
                DOCUMENT,
                {...OP, documentId: '66666666-6666-4666-8666-666666666666'},
            )
        })

        expect(result.current.operationTarget).toBeNull()
        expect(setError).toHaveBeenCalledWith(
            'Документ или операция не принадлежат выбранной базе. Обновите список.',
        )
    })

    it('accepts HTTP 202 as DELETING, not as physical deletion completion', async () => {
        capability.mockResolvedValue(true)
        history.mockResolvedValue([])
        accept.mockResolvedValue(ACCEPTED)

        const refresh = vi.fn().mockResolvedValue(undefined)
        const {result} = renderHook(
            () => useKnowledgeDeletionController(makeConfig(true, BASE, {refresh})),
        )

        await waitFor(() => expect(result.current.available).toBe(true))

        act(() => result.current.requestDeletion(DOCUMENT))

        expect(result.current.target).not.toBeNull()

        await act(async () => {
            await result.current.confirmDeletion()
        })

        expect(accept).toHaveBeenCalledTimes(1)
        expect(accept).toHaveBeenCalledWith(
            BASE,
            DOCUMENT.id,
            DOCUMENT.version,
            'document-idempotency-key',
        )
        expect(result.current.visibleOperation?.state).toBe('DELETING')
        expect(result.current.target).toBeNull()
        expect(refresh).toHaveBeenCalledTimes(1)
        expect(clearKey).toHaveBeenCalledWith(
            'document',
            BASE,
            DOCUMENT.id,
            DOCUMENT.version,
        )
    })

    it('rechecks capability immediately before DELETE and blocks if it was disabled', async () => {
        capability.mockResolvedValueOnce(true).mockResolvedValueOnce(false)
        history.mockResolvedValue([])

        const {result} = renderHook(
            () => useKnowledgeDeletionController(makeConfig()),
        )

        await waitFor(() => expect(result.current.available).toBe(true))
        act(() => result.current.requestDeletion(DOCUMENT))

        await act(async () => {
            await expect(result.current.confirmDeletion())
                .rejects.toThrow('Удаление отключено')
        })

        expect(accept).not.toHaveBeenCalled()
        expect(result.current.available).toBe(false)
        expect(result.current.busy).toBe(false)
    })

    it('blocks DELETE if the preflight history cannot be read', async () => {
        capability.mockResolvedValue(true)
        history
            .mockResolvedValueOnce([])
            .mockRejectedValueOnce(new Error('history unavailable'))

        const {result} = renderHook(
            () => useKnowledgeDeletionController(makeConfig()),
        )

        await waitFor(() => expect(result.current.available).toBe(true))
        act(() => result.current.requestDeletion(DOCUMENT))

        await act(async () => {
            await expect(result.current.confirmDeletion())
                .rejects.toThrow('history unavailable')
        })

        expect(accept).not.toHaveBeenCalled()
        expect(result.current.available).toBe(false)
        expect(result.current.historyError).toBe(true)
        expect(result.current.busy).toBe(false)
    })

    it('does not send DELETE if another operation appeared during confirmation', async () => {
        capability.mockResolvedValue(true)
        history
            .mockResolvedValueOnce([])
            .mockResolvedValueOnce([ACCEPTED])

        const {result} = await renderAndConfirmDocumentDeletion()

        expect(accept).not.toHaveBeenCalled()
        expect(result.current.target).toBeNull()
        expect(result.current.visibleOperation).toEqual(ACCEPTED)
    })

    it('rejects a DELETE response for another document and fails closed', async () => {
        capability.mockResolvedValue(true)
        history.mockResolvedValue([])
        accept.mockResolvedValue({
            ...ACCEPTED,
            documentId: '66666666-6666-4666-8666-666666666666',
        })

        const setError = vi.fn()
        const {result} = renderHook(
            () => useKnowledgeDeletionController(makeConfig(true, BASE, {setError})),
        )

        await waitFor(() => expect(result.current.available).toBe(true))
        act(() => result.current.requestDeletion(DOCUMENT))

        await act(async () => {
            await expect(result.current.confirmDeletion())
                .rejects.toThrow('Сервер вернул операцию другого документа')
        })

        expect(accept).toHaveBeenCalledTimes(1)
        expect(result.current.available).toBe(false)
        expect(result.current.historyError).toBe(true)
        expect(result.current.target).not.toBeNull()
        expect(result.current.busy).toBe(false)
        expect(clearKey).not.toHaveBeenCalled()
        expect(setError).toHaveBeenCalled()
    })

    it('does not accept an operation ID already associated with another document', async () => {
        capability.mockResolvedValue(true)
        history.mockResolvedValue([{
            ...OP,
            documentId: '77777777-7777-4777-8777-777777777777',
        }])
        accept.mockResolvedValue(ACCEPTED)

        const {result} = renderHook(
            () => useKnowledgeDeletionController(makeConfig()),
        )

        await waitFor(() => expect(result.current.available).toBe(true))
        act(() => result.current.requestDeletion(DOCUMENT))

        await act(async () => {
            await expect(result.current.confirmDeletion())
                .rejects.toThrow('повторный идентификатор операции')
        })

        expect(result.current.available).toBe(false)
        expect(result.current.historyError).toBe(true)
        expect(result.current.target).not.toBeNull()
        expect(clearKey).not.toHaveBeenCalled()
    })

    it('reconciles a lost HTTP response from the server history without a second DELETE', async () => {
        capability.mockResolvedValue(true)
        history
            .mockResolvedValueOnce([])
            .mockResolvedValueOnce([])
            .mockResolvedValueOnce([ACCEPTED])
        accept.mockRejectedValueOnce(new Error('HTTP response lost'))

        const {result} = await renderAndConfirmDocumentDeletion()

        expect(accept).toHaveBeenCalledTimes(1)
        expect(result.current.target).toBeNull()
        expect(result.current.visibleOperation).toEqual(ACCEPTED)
        expect(clearKey).toHaveBeenCalledWith(
            'document',
            BASE,
            DOCUMENT.id,
            DOCUMENT.version,
        )
    })

    it('keeps the same idempotency key and version when reconciliation is inconclusive', async () => {
        capability.mockResolvedValue(true)
        history
            .mockResolvedValueOnce([]) // initial history
            .mockResolvedValueOnce([]) // preflight
            .mockRejectedValueOnce(new Error('history unavailable during reconcile'))
            .mockResolvedValueOnce([]) // second preflight
        accept
            .mockRejectedValueOnce(new Error('HTTP response lost'))
            .mockResolvedValueOnce(ACCEPTED)

        const {result} = renderHook(
            () => useKnowledgeDeletionController(makeConfig()),
        )

        await waitFor(() => expect(result.current.available).toBe(true))
        act(() => result.current.requestDeletion(DOCUMENT))

        await act(async () => {
            await expect(result.current.confirmDeletion())
                .rejects.toThrow('HTTP response lost')
        })

        expect(result.current.target).not.toBeNull()

        await act(async () => {
            await result.current.confirmDeletion()
        })

        expect(accept).toHaveBeenCalledTimes(2)
        expect(accept.mock.calls[0]?.[2]).toBe(accept.mock.calls[1]?.[2])
        expect(accept.mock.calls[0]?.[3]).toBe(accept.mock.calls[1]?.[3])
        expect(getKey).toHaveBeenCalledTimes(1)
        expect(result.current.visibleOperation?.state).toBe('DELETING')
    })

    it('does not undo accepted deletion when catalog refresh fails', async () => {
        capability.mockResolvedValue(true)
        history.mockResolvedValue([])
        accept.mockResolvedValue(ACCEPTED)

        const setError = vi.fn()
        const refresh = vi.fn().mockRejectedValue(new Error('catalog refresh failed'))
        const {result} = renderHook(
            () => useKnowledgeDeletionController(makeConfig(true, BASE, {setError, refresh})),
        )

        await waitFor(() => expect(result.current.available).toBe(true))
        act(() => result.current.requestDeletion(DOCUMENT))

        await act(async () => {
            await result.current.confirmDeletion()
        })

        expect(result.current.visibleOperation).toEqual(ACCEPTED)
        expect(result.current.target).toBeNull()
        expect(setError).toHaveBeenCalledWith(
            'Удаление принято сервером, но список документов не обновился. Обновите страницу.',
        )
    })

    it('prevents two concurrent confirmations from sending two DELETE requests', async () => {
        capability.mockResolvedValue(true)
        history.mockResolvedValue([])

        const pendingAccept = deferred<KnowledgeDocumentDeletion>()
        accept.mockReturnValue(pendingAccept.promise)

        const {result} = renderHook(
            () => useKnowledgeDeletionController(makeConfig()),
        )

        await waitFor(() => expect(result.current.available).toBe(true))
        act(() => result.current.requestDeletion(DOCUMENT))

        let first!: Promise<void>
        act(() => {
            first = result.current.confirmDeletion()
        })

        await waitFor(() => expect(accept).toHaveBeenCalledTimes(1))

        await act(async () => {
            await result.current.confirmDeletion()
        })

        expect(accept).toHaveBeenCalledTimes(1)

        await act(async () => {
            pendingAccept.resolve(ACCEPTED)
            await first
        })

        expect(result.current.visibleOperation).toEqual(ACCEPTED)
        expect(result.current.busy).toBe(false)
    })

    it('does not apply a pending DELETE response after switching to another base', async () => {
        capability.mockResolvedValue(true)
        history.mockResolvedValue([])

        const pendingAccept = deferred<KnowledgeDocumentDeletion>()
        accept.mockReturnValue(pendingAccept.promise)

        const {result, rerender} = renderHook(
            ({baseId}) => useKnowledgeDeletionController(makeConfig(true, baseId)),
            {initialProps: {baseId: BASE}},
        )

        await waitFor(() => expect(result.current.available).toBe(true))
        act(() => result.current.requestDeletion(DOCUMENT))

        let request!: Promise<void>
        act(() => {
            request = result.current.confirmDeletion()
        })

        await waitFor(() => expect(accept).toHaveBeenCalledTimes(1))

        rerender({baseId: OTHER_BASE})
        await waitFor(() => expect(result.current.available).toBe(true))

        await act(async () => {
            pendingAccept.resolve(ACCEPTED)
            await request
        })

        expect(result.current.operations).toEqual([])
        expect(result.current.visibleOperation).toBeNull()
        expect(result.current.target).toBeNull()
    })
})


describe('useKnowledgeDeletionController: canceled intent retention', () => {
    it('reuses the same key when the user cancels and reopens the same document intent', async () => {
        capability.mockResolvedValue(true)
        history.mockResolvedValue([])

        const {result} = renderHook(
            () => useKnowledgeDeletionController(makeConfig()),
        )

        await waitFor(() => expect(result.current.available).toBe(true))

        act(() => result.current.requestDeletion(DOCUMENT))
        const firstKey = result.current.target?.key

        expect(firstKey).toBe('document-idempotency-key')

        act(() => result.current.cancelDeletion())
        expect(result.current.target).toBeNull()

        act(() => result.current.requestDeletion(DOCUMENT))

        expect(result.current.target?.key).toBe(firstKey)
        expect(getKey).toHaveBeenCalledTimes(1)
        expect(accept).not.toHaveBeenCalled()
    })
})

