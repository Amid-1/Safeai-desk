// frontend/src/features/knowledge/ui/KnowledgeBaseDeleteAction.test.tsx
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
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

describe('KnowledgeBaseDeleteAction', () => {
    it('does not allow destructive POST when server refuses capability', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(false)
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={vi.fn()}/>)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
        expect(await screen.findByText(/пока не включено на сервере/)).toBeInTheDocument()
        fireEvent.change(screen.getByRole('textbox', {name: 'Название базы'}),
            {target: {value: base.name}})
        expect(screen.getByRole('button', {name: 'Удалить базу безвозвратно'})).toBeDisabled()
        expect(acceptBaseDeletion).not.toHaveBeenCalled()
    })

    it('requires exact name and sends expected version with stable idempotency key', async () => {
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
        const button = screen.getByRole('button', {name: 'Удалить базу безвозвратно'})
        expect(button).toBeDisabled()
        fireEvent.change(screen.getByRole('textbox', {name: 'Название базы'}),
            {target: {value: base.name}})
        await waitFor(() => expect(button).toBeEnabled())
        fireEvent.click(button)
        await waitFor(() => expect(acceptBaseDeletion).toHaveBeenCalledWith(
            base.id, 3, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'))
        await waitFor(() => expect(accepted).toHaveBeenCalledTimes(1))
    })
})
