// frontend/src/features/knowledge/ui/KnowledgeBaseDeleteAction.test.tsx
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import type {KnowledgeBase} from '../../../entities/knowledge/api/knowledgeApi'
import {acceptBaseDeletion, baseDeletionCapability} from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'
import {KnowledgeBaseDeleteAction} from './KnowledgeBaseDeleteAction'

vi.mock('../../../entities/knowledge/api/knowledgeBaseDeletionApi', () => ({
    acceptBaseDeletion: vi.fn(),
    baseDeletionCapability: vi.fn(),
}))
vi.mock('../../../shared/lib/secureUuid', () => ({
    createSecureUuid: () => 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
}))

const base: KnowledgeBase = {
    id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    organizationId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    name: 'Закрытая база', description: null, visibility: 'MEMBERS',
    enabled: true, version: 3,
    createdByUserId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
    createdAt: '2026-10-28T00:00:00Z', updatedAt: '2026-10-28T00:00:00Z',
}

beforeEach(() => vi.clearAllMocks())

describe('KnowledgeBaseDeleteAction', () => {
    it('does not allow DELETE or offer a typing field when server refuses capability', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(false)
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={vi.fn()}/>)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
        expect(await screen.findByText('Удаление баз знаний пока отключено в настройках системы.'))
            .toBeInTheDocument()
        expect(screen.getByText('Чтобы воспользоваться этой функцией, её необходимо включить и проверить.'))
            .toBeInTheDocument()
        expect(screen.queryByText(/названием базы или вашей ролью/)).not.toBeInTheDocument()
        expect(screen.queryByRole('textbox', {name: 'Название базы знаний'})).not.toBeInTheDocument()
        expect(screen.queryByRole('button', {name: 'Подтвердить удаление базы'})).not.toBeInTheDocument()
        expect(screen.getByRole('button', {name: 'Закрыть'})).toBeEnabled()
        expect(acceptBaseDeletion).not.toHaveBeenCalled()
    })

    it('allows case differences but preserves entity identity and the idempotency key', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        vi.mocked(acceptBaseDeletion).mockResolvedValue({
            operationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
            knowledgeBaseId: base.id, state: 'DELETING',
            createdAt: '2026-10-28T00:00:00Z', completedAt: null, errorCode: null,
        })
        const accepted = vi.fn()
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={accepted}/>)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
        await waitFor(() => expect(baseDeletionCapability).toHaveBeenCalledWith(base.id, expect.any(AbortSignal)))
        const button = screen.getByRole('button', {name: 'Подтвердить удаление базы'})
        const input = await screen.findByRole('textbox', {name: 'Название базы знаний'})
        expect(screen.getByText(/После подтверждения система начнёт удаление/)).toBeInTheDocument()
        expect(button).toBeDisabled()
        fireEvent.change(input, {target: {value: 'ЗАКРЫТАЯ БАЗА'}})
        await waitFor(() => expect(button).toBeEnabled())
        fireEvent.click(button)
        await waitFor(() => expect(acceptBaseDeletion).toHaveBeenCalledWith(
            base.id, 3, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'))
        expect(accepted).toHaveBeenCalledTimes(1)
    })

    it('rejects confusables even after the capability was confirmed', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        const xBase = {...base, name: 'X3'}
        render(<KnowledgeBaseDeleteAction base={xBase} onAccepted={vi.fn()}/>)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
        const input = await screen.findByRole('textbox', {name: 'Название базы знаний'})
        fireEvent.change(input, {target: {value: 'х3'}})
        expect(screen.getByRole('button', {name: 'Подтвердить удаление базы'})).toBeDisabled()
        expect(acceptBaseDeletion).not.toHaveBeenCalled()
    })

    it('keeps the same idempotency key on a failed submission and permits retry', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        vi.mocked(acceptBaseDeletion).mockRejectedValueOnce(new Error('network lost'))
            .mockResolvedValueOnce({
                operationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
                knowledgeBaseId: base.id, state: 'DELETING',
                createdAt: '2026-10-28T00:00:00Z', completedAt: null, errorCode: null,
            })
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={vi.fn()}/>)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
        const input = await screen.findByRole('textbox', {name: 'Название базы знаний'})
        fireEvent.change(input, {target: {value: base.name}})
        const button = screen.getByRole('button', {name: 'Подтвердить удаление базы'})
        await waitFor(() => expect(button).toBeEnabled())
        fireEvent.click(button)
        await waitFor(() => expect(acceptBaseDeletion).toHaveBeenCalledTimes(1))
        await waitFor(() => expect(button).toBeEnabled())
        fireEvent.click(button)
        await waitFor(() => expect(acceptBaseDeletion).toHaveBeenCalledTimes(2))
        const calls = vi.mocked(acceptBaseDeletion).mock.calls
        expect(calls[0][2]).toBe(calls[1][2])
    })
})
