// frontend/src/features/knowledge/ui/KnowledgeBaseDeleteAction.test.tsx
import {act, fireEvent, render, screen, waitFor} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'

import type {KnowledgeBase} from '../../../entities/knowledge/api/knowledgeApi'
import {
    acceptBaseDeletion,
    baseDeletionCapability,
    getBaseDeletion,
    listBaseDeletions,
} from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'
import type {BaseDeletionOperation} from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'
import {ApiError} from '../../../shared/api/http'
import {
    clearKnowledgeDeletionRequestKey,
    getKnowledgeDeletionRequestKey,
} from '../lib/knowledgeDeletionRequestKey'
import {deferred, flushMicrotasks} from '../test/knowledgeDeletionTestUtils'
import {KnowledgeBaseDeleteAction} from './KnowledgeBaseDeleteAction'

vi.mock('../../../entities/knowledge/api/knowledgeBaseDeletionApi', () => ({
    acceptBaseDeletion: vi.fn(),
    baseDeletionCapability: vi.fn(),
    getBaseDeletion: vi.fn(),
    listBaseDeletions: vi.fn(),
}))

vi.mock('../lib/knowledgeDeletionRequestKey', () => ({
    getKnowledgeDeletionRequestKey: vi.fn(),
    clearKnowledgeDeletionRequestKey: vi.fn(),
}))

const BASE_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const OTHER_BASE_ID = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'

const BASE: KnowledgeBase = {
    id: BASE_ID,
    organizationId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    name: 'Закрытая база',
    description: null,
    visibility: 'MEMBERS',
    enabled: true,
    version: 3,
    createdByUserId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
    createdAt: '2026-10-28T00:00:00Z',
    updatedAt: '2026-10-28T00:00:00Z',
}

const OTHER_BASE: KnowledgeBase = {
    ...BASE,
    id: OTHER_BASE_ID,
    name: 'Другая база',
}

const UPDATED_BASE: KnowledgeBase = {
    ...BASE,
    version: 4,
}

const OPERATION: BaseDeletionOperation = {
    operationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    knowledgeBaseId: BASE_ID,
    state: 'DELETING',
    createdAt: '2026-10-28T00:00:00Z',
    completedAt: null,
    errorCode: null,
}

const capability = vi.mocked(baseDeletionCapability)
const history = vi.mocked(listBaseDeletions)
const current = vi.mocked(getBaseDeletion)
const accept = vi.mocked(acceptBaseDeletion)
const getKey = vi.mocked(getKnowledgeDeletionRequestKey)
const clearKey = vi.mocked(clearKnowledgeDeletionRequestKey)

function notFound() {
    return new ApiError('Не найдено', {}, 404)
}

function expectBaseRequestKeyCleared() {
    expect(clearKey).toHaveBeenCalledWith('base', BASE_ID, BASE_ID, BASE.version)
}

async function openConfirmation() {
    fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
    return screen.findByRole('textbox', {name: 'Название базы знаний'})
}

async function fillConfirmation(name = BASE.name) {
    const input = await openConfirmation()
    fireEvent.change(input, {target: {value: name}})
    return screen.getByRole('button', {name: 'Подтвердить удаление базы'})
}

beforeEach(() => {
    vi.clearAllMocks()

    capability.mockReset().mockResolvedValue(false)
    history.mockReset().mockResolvedValue([])
    current.mockReset().mockRejectedValue(notFound())
    accept.mockReset()

    getKey.mockReset().mockReturnValue('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
    clearKey.mockReset()
})

describe('KnowledgeBaseDeleteAction: initial server inspection', () => {
    it('shows the informational dialog when destructive capability is disabled', async () => {
        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))

        expect(await screen.findByText(
            'Удаление баз знаний пока отключено в настройках системы.',
        )).toBeInTheDocument()

        expect(screen.getByText('Ограничение безопасности')).toBeInTheDocument()
        expect(screen.queryByRole('textbox', {name: 'Название базы знаний'})).not.toBeInTheDocument()
        expect(screen.getByRole('button', {name: 'Понятно'})).toBeEnabled()
        expect(accept).not.toHaveBeenCalled()
    })

    it('fails closed if the operation history cannot be loaded', async () => {
        capability.mockResolvedValue(true)
        history.mockRejectedValue(new Error('history unavailable'))

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))

        expect(await screen.findByText(/Новый запрос запрещён/)).toBeInTheDocument()
        expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
        expect(accept).not.toHaveBeenCalled()
    })

    it('fails closed on an unknown current-operation error instead of treating it as 404', async () => {
        capability.mockResolvedValue(true)
        current.mockRejectedValue(new Error('database unavailable'))

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))

        expect(await screen.findByText(/Не удалось сверить текущую операцию базы/))
            .toBeInTheDocument()
        expect(accept).not.toHaveBeenCalled()
    })

    it('treats only API 404 as absence of a current operation', async () => {
        capability.mockResolvedValue(true)

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        expect(await openConfirmation()).toBeInTheDocument()
        expect(current).toHaveBeenCalledWith(BASE_ID, expect.any(AbortSignal))
    })

    it('shows a current operation even if the destructive capability is disabled', async () => {
        current.mockResolvedValue(OPERATION)

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))

        expect(await screen.findByText('Удаление выполняется')).toBeInTheDocument()
        expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
        expect(accept).not.toHaveBeenCalled()
    })

    it('finds an old operation through current even if it fell out of the latest history page', async () => {
        capability.mockResolvedValue(true)
        current.mockResolvedValue({
            ...OPERATION,
            state: 'NEEDS_REVIEW',
        })

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))

        expect(await screen.findByText('Требуется проверка администратором'))
            .toBeInTheDocument()
        expect(accept).not.toHaveBeenCalled()
    })

    it('does not create a new operation when history contains a retention block', async () => {
        capability.mockResolvedValue(true)
        history.mockResolvedValue([{
            ...OPERATION,
            state: 'BLOCKED_BY_RETENTION',
            errorCode: 'RETENTION',
        }])

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))

        expect(await screen.findByText('Удаление ограничено сроком хранения'))
            .toBeInTheDocument()
        expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
        expect(accept).not.toHaveBeenCalled()
    })

    it('rejects a current operation that belongs to another base', async () => {
        capability.mockResolvedValue(true)
        current.mockResolvedValue({
            ...OPERATION,
            knowledgeBaseId: OTHER_BASE_ID,
        })

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))

        expect(await screen.findByText(/Сервер вернул операцию другой базы/))
            .toBeInTheDocument()
        expect(accept).not.toHaveBeenCalled()
    })

    it('does not allow a DELETING base without a discoverable operation to be deleted again', async () => {
        capability.mockResolvedValue(true)

        render(
            <KnowledgeBaseDeleteAction
                base={{...BASE, lifecycleState: 'DELETING'}}
                onAccepted={vi.fn()}
            />,
        )

        fireEvent.click(screen.getByRole('button', {name: 'Статус удаления…'}))

        expect(await screen.findByText(/База находится в удалении/)).toBeInTheDocument()
        expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
        expect(accept).not.toHaveBeenCalled()
    })

    it('filters history by the requested base before using it as fallback evidence', async () => {
        capability.mockResolvedValue(true)
        history.mockResolvedValue([{
            ...OPERATION,
            knowledgeBaseId: OTHER_BASE_ID,
        }])

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        expect(await openConfirmation()).toBeInTheDocument()
        expect(accept).not.toHaveBeenCalled()
    })
})

describe('KnowledgeBaseDeleteAction: preflight and mutation', () => {
    it('rechecks capability immediately before DELETE and blocks if the flag was disabled', async () => {
        capability.mockResolvedValueOnce(true).mockResolvedValueOnce(false)

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        const button = await fillConfirmation()
        fireEvent.click(button)

        await waitFor(() => {
            expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
        })

        expect(accept).not.toHaveBeenCalled()
        expect(screen.getByText('Удаление отключено на сервере.')).toBeInTheDocument()
    })

    it('rechecks history and does not send DELETE if another operation appeared after opening', async () => {
        capability.mockResolvedValue(true)
        history
            .mockResolvedValueOnce([])
            .mockResolvedValueOnce([{
                ...OPERATION,
                state: 'BLOCKED_BY_RETENTION',
                errorCode: 'RETENTION',
            }])

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        const button = await fillConfirmation()
        fireEvent.click(button)

        expect(await screen.findByText('Удаление ограничено сроком хранения'))
            .toBeInTheDocument()
        expect(accept).not.toHaveBeenCalled()
    })

    it('rechecks current operation before DELETE even if the history page is empty', async () => {
        capability.mockResolvedValue(true)
        current
            .mockRejectedValueOnce(notFound())
            .mockResolvedValueOnce(OPERATION)

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        const button = await fillConfirmation()
        fireEvent.click(button)

        expect(await screen.findByText('Удаление выполняется')).toBeInTheDocument()
        expect(accept).not.toHaveBeenCalled()
    })

    it('blocks DELETE when preflight history fails', async () => {
        capability.mockResolvedValue(true)
        history
            .mockResolvedValueOnce([])
            .mockRejectedValueOnce(new Error('history failed before mutation'))

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        const button = await fillConfirmation()
        fireEvent.click(button)

        expect(await screen.findByText(/Не удалось получить историю операций/))
            .toBeInTheDocument()
        expect(accept).not.toHaveBeenCalled()
    })

    it('uses the checked base version and a stable idempotency key for a successful 202', async () => {
        capability.mockResolvedValue(true)
        accept.mockResolvedValue(OPERATION)

        const onAccepted = vi.fn()
        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={onAccepted}/>)

        const input = await openConfirmation()
        const button = screen.getByRole('button', {name: 'Подтвердить удаление базы'})

        expect(button).toBeDisabled()
        fireEvent.click(button)
        expect(button).toBeDisabled()

        // The name must be entered before the submit button becomes enabled.
        fireEvent.change(input, {target: {value: BASE.name}})
        expect(button).toBeEnabled()
        fireEvent.click(button)

        await waitFor(() => {
            expect(accept).toHaveBeenCalledWith(
                BASE_ID,
                BASE.version,
                'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
            )
        })

        expect(await screen.findByText('Удаление выполняется')).toBeInTheDocument()
        expect(screen.queryByText('Удаление завершено')).not.toBeInTheDocument()
        expect(onAccepted).not.toHaveBeenCalled()
        expectBaseRequestKeyCleared()

        fireEvent.click(screen.getByRole('button', {name: 'Закрыть'}))
        expect(onAccepted).toHaveBeenCalledTimes(1)
    })

    it('does not accept a response containing another base ID', async () => {
        capability.mockResolvedValue(true)
        accept.mockResolvedValue({
            ...OPERATION,
            knowledgeBaseId: OTHER_BASE_ID,
        })

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        const button = await fillConfirmation()
        fireEvent.click(button)

        expect(await screen.findByText(/Сервер вернул идентификатор другой базы/))
            .toBeInTheDocument()
        expect(accept).toHaveBeenCalledTimes(1)
        expect(screen.queryByText('Удаление завершено')).not.toBeInTheDocument()
        expect(clearKey).not.toHaveBeenCalled()
    })

    it('calls onAccepted only after a successfully accepted operation dialog is closed', async () => {
        capability.mockResolvedValue(true)
        accept.mockResolvedValue(OPERATION)

        const onAccepted = vi.fn()
        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={onAccepted}/>)

        const button = await fillConfirmation()
        fireEvent.click(button)

        expect(await screen.findByText('Удаление выполняется')).toBeInTheDocument()
        expect(onAccepted).not.toHaveBeenCalled()

        fireEvent.click(screen.getByRole('button', {name: 'Закрыть'}))
        expect(onAccepted).toHaveBeenCalledTimes(1)
    })

    it('does not call onAccepted when closing an operation that already existed', async () => {
        current.mockResolvedValue(OPERATION)

        const onAccepted = vi.fn()
        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={onAccepted}/>)

        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
        expect(await screen.findByText('Удаление выполняется')).toBeInTheDocument()

        fireEvent.click(screen.getByRole('button', {name: 'Закрыть'}))
        expect(onAccepted).not.toHaveBeenCalled()
    })

    it('preserves the same key and original version after an inconclusive network failure', async () => {
        capability.mockResolvedValue(true)
        accept
            .mockRejectedValueOnce(new Error('network lost'))
            .mockResolvedValueOnce(OPERATION)

        const onAccepted = vi.fn()
        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={onAccepted}/>)

        const button = await fillConfirmation()
        fireEvent.click(button)

        await waitFor(() => expect(accept).toHaveBeenCalledTimes(1))
        await waitFor(() => expect(button).toBeEnabled())

        fireEvent.click(button)

        await waitFor(() => expect(accept).toHaveBeenCalledTimes(2))

        const calls = accept.mock.calls
        expect(calls[0]?.[0]).toBe(BASE_ID)
        expect(calls[0]?.[1]).toBe(calls[1]?.[1])
        expect(calls[0]?.[2]).toBe(calls[1]?.[2])
        expect(calls[0]?.[1]).toBe(BASE.version)
        expect(getKey).toHaveBeenCalledTimes(1)

        expect(await screen.findByText('Удаление выполняется')).toBeInTheDocument()
        expect(onAccepted).not.toHaveBeenCalled()
    })

    it('reconciles a lost response through current even when the history list is empty', async () => {
        capability.mockResolvedValue(true)
        current
            .mockRejectedValueOnce(notFound()) // initial inspection
            .mockRejectedValueOnce(notFound()) // preflight
            .mockResolvedValueOnce(OPERATION) // reconcile
        accept.mockRejectedValueOnce(new Error('connection lost'))

        const onAccepted = vi.fn()
        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={onAccepted}/>)

        const button = await fillConfirmation()
        fireEvent.click(button)

        expect(await screen.findByText('Удаление выполняется')).toBeInTheDocument()
        expect(accept).toHaveBeenCalledTimes(1)
        expectBaseRequestKeyCleared()

        fireEvent.click(screen.getByRole('button', {name: 'Закрыть'}))
        expect(onAccepted).toHaveBeenCalledTimes(1)
    })

    it('reconciles a lost response from history if current returns 404', async () => {
        capability.mockResolvedValue(true)
        history
            .mockResolvedValueOnce([]) // initial inspection
            .mockResolvedValueOnce([]) // preflight
            .mockResolvedValueOnce([OPERATION]) // reconcile
        current.mockRejectedValue(notFound())
        accept.mockRejectedValueOnce(new Error('connection lost'))

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        const button = await fillConfirmation()
        fireEvent.click(button)

        expect(await screen.findByText('Удаление выполняется')).toBeInTheDocument()
        expect(accept).toHaveBeenCalledTimes(1)
    })

    it('blocks retry if reconciliation itself has an unknown server state', async () => {
        capability.mockResolvedValue(true)
        current
            .mockRejectedValueOnce(notFound())
            .mockRejectedValueOnce(notFound())
            .mockRejectedValueOnce(new Error('current unavailable during reconcile'))
        accept.mockRejectedValueOnce(new Error('network lost'))

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        const button = await fillConfirmation()
        fireEvent.click(button)

        expect(await screen.findByText(/Не удалось достоверно сверить удаление/))
            .toBeInTheDocument()
        expect(accept).toHaveBeenCalledTimes(1)
        expect(screen.queryByRole('button', {name: 'Подтвердить удаление базы'}))
            .not.toBeInTheDocument()
    })

    it('prevents concurrent confirmations from sending two DELETE requests', async () => {
        capability.mockResolvedValue(true)

        const pendingAccept = deferred<BaseDeletionOperation>()
        accept.mockReturnValue(pendingAccept.promise)

        render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

        const button = await fillConfirmation()

        fireEvent.click(button)
        await waitFor(() => expect(accept).toHaveBeenCalledTimes(1))

        fireEvent.click(button)
        expect(accept).toHaveBeenCalledTimes(1)

        await act(async () => {
            pendingAccept.resolve(OPERATION)
            await Promise.resolve()
        })

        expect(await screen.findByText('Удаление выполняется')).toBeInTheDocument()
        expect(accept).toHaveBeenCalledTimes(1)
    })

    it('rechecks a changed base version before opening the final confirmation', async () => {
        capability.mockResolvedValue(true)
        accept.mockResolvedValue(OPERATION)

        const {rerender} = render(
            <KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>,
        )

        await openConfirmation()

        rerender(
            <KnowledgeBaseDeleteAction base={UPDATED_BASE} onAccepted={vi.fn()}/>,
        )

        const input = await screen.findByRole('textbox', {name: 'Название базы знаний'})
        fireEvent.change(input, {target: {value: BASE.name}})
        fireEvent.click(screen.getByRole('button', {name: 'Подтвердить удаление базы'}))

        await waitFor(() => {
            expect(accept).toHaveBeenCalledWith(
                BASE_ID,
                4,
                'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
            )
        })
    })

    it('retains the original request version when retrying after the card version changes', async () => {
        capability.mockResolvedValue(true)
        accept
            .mockRejectedValueOnce(new Error('network lost'))
            .mockResolvedValueOnce(OPERATION)

        const {rerender} = render(
            <KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>,
        )

        let button = await fillConfirmation()
        fireEvent.click(button)

        await waitFor(() => expect(accept).toHaveBeenCalledTimes(1))
        await waitFor(() => expect(button).toBeEnabled())

        rerender(
            <KnowledgeBaseDeleteAction base={UPDATED_BASE} onAccepted={vi.fn()}/>,
        )

        const input = await screen.findByRole('textbox', {name: 'Название базы знаний'})
        fireEvent.change(input, {target: {value: BASE.name}})
        button = screen.getByRole('button', {name: 'Подтвердить удаление базы'})
        await waitFor(() => expect(button).toBeEnabled())
        fireEvent.click(button)

        await waitFor(() => expect(accept).toHaveBeenCalledTimes(2))

        expect(accept.mock.calls[0]?.[1]).toBe(3)
        expect(accept.mock.calls[1]?.[1]).toBe(3)
        expect(accept.mock.calls[0]?.[2]).toBe(accept.mock.calls[1]?.[2])
    })

    it('does not apply a late current-operation response after switching bases', async () => {
        capability.mockResolvedValue(true)

        const oldCurrent = deferred<BaseDeletionOperation>()
        current
            .mockImplementationOnce(() => oldCurrent.promise)
            .mockRejectedValueOnce(notFound())

        const {rerender} = render(
            <KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>,
        )

        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))

        rerender(
            <KnowledgeBaseDeleteAction base={OTHER_BASE} onAccepted={vi.fn()}/>,
        )

        expect(await screen.findByRole('textbox', {name: 'Название базы знаний'}))
            .toBeInTheDocument()

        await act(async () => {
            oldCurrent.resolve(OPERATION)
            await oldCurrent.promise
        })

        expect(screen.queryByText('Удаление выполняется')).not.toBeInTheDocument()
        expect(accept).not.toHaveBeenCalled()
    })
})

describe('KnowledgeBaseDeleteAction: operation polling lifecycle', () => {
    it('polls the same operation and stops after DELETED', async () => {
        vi.useFakeTimers()

        current
            .mockResolvedValueOnce(OPERATION)
            .mockResolvedValueOnce({
                ...OPERATION,
                state: 'DELETED',
                completedAt: '2026-10-28T00:10:00Z',
            })

        try {
            render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

            fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
            await flushMicrotasks()

            expect(screen.getByText('Удаление выполняется')).toBeInTheDocument()

            await act(async () => {
                await vi.advanceTimersByTimeAsync(12_000)
            })

            expect(screen.getByText('Удаление завершено')).toBeInTheDocument()
            expect(current).toHaveBeenCalledTimes(2)

            await act(async () => {
                await vi.advanceTimersByTimeAsync(24_000)
            })

            expect(current).toHaveBeenCalledTimes(2)
        } finally {
            vi.useRealTimers()
        }
    })

    it('reports a different operation ID returned by polling instead of replacing the current one', async () => {
        vi.useFakeTimers()

        current
            .mockResolvedValueOnce(OPERATION)
            .mockResolvedValueOnce({
                ...OPERATION,
                operationId: 'ffffffff-ffff-4fff-8fff-ffffffffffff',
            })

        try {
            render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

            fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
            await flushMicrotasks()

            await act(async () => {
                await vi.advanceTimersByTimeAsync(12_000)
            })

            expect(screen.getByText(
                'Сервер вернул другую операцию удаления. Обратитесь к администратору.',
            )).toBeInTheDocument()
            expect(screen.getByText('Удаление выполняется')).toBeInTheDocument()
        } finally {
            vi.useRealTimers()
        }
    })

    it('stops polling when the operation dialog is closed', async () => {
        vi.useFakeTimers()
        current.mockResolvedValue(OPERATION)

        try {
            render(<KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>)

            fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
            await flushMicrotasks()

            expect(current).toHaveBeenCalledTimes(1)

            fireEvent.click(screen.getByRole('button', {name: 'Закрыть'}))

            await act(async () => {
                await vi.advanceTimersByTimeAsync(24_000)
            })

            expect(current).toHaveBeenCalledTimes(1)
        } finally {
            vi.useRealTimers()
        }
    })

    it('does not apply a late polling response after the component changes base', async () => {
        vi.useFakeTimers()

        const latePoll = deferred<BaseDeletionOperation>()
        current
            .mockResolvedValueOnce(OPERATION) // initial inspection
            .mockImplementationOnce(() => latePoll.promise) // poll old base
            .mockRejectedValueOnce(notFound()) // initial inspection new base

        try {
            const {rerender} = render(
                <KnowledgeBaseDeleteAction base={BASE} onAccepted={vi.fn()}/>,
            )

            fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
            await flushMicrotasks()

            await act(async () => {
                const advance = vi.advanceTimersByTimeAsync(12_000)
                await Promise.resolve()
                await advance
            })

            rerender(
                <KnowledgeBaseDeleteAction base={OTHER_BASE} onAccepted={vi.fn()}/>,
            )

            await flushMicrotasks()
            expect(screen.getByRole('textbox', {name: 'Название базы знаний'}))
                .toBeInTheDocument()

            await act(async () => {
                latePoll.resolve({
                    ...OPERATION,
                    state: 'DELETED',
                    completedAt: '2026-10-28T00:10:00Z',
                })
                await latePoll.promise
            })

            expect(screen.queryByText('Удаление завершено')).not.toBeInTheDocument()
        } finally {
            vi.useRealTimers()
        }
    })
})
