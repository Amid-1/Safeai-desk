// frontend/src/features/knowledge/ui/KnowledgeMembersModal.test.tsx
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { KnowledgeBase, KnowledgeBaseMember } from '../../../entities/knowledge/api/knowledgeApi'
import {
    getKnowledgeBaseMembers,
    removeKnowledgeBaseMember,
    updateKnowledgeBaseMember,
} from '../../../entities/knowledge/api/knowledgeApi'
import KnowledgeMembersModal from './KnowledgeMembersModal'

const auth = vi.hoisted(() => ({ userId: '11111111-1111-4111-8111-111111111111' }))

vi.mock('../../auth/state/useAuth', () => ({
    useAuth: () => ({ currentUser: { id: auth.userId, roles: ['ADMIN'] } }),
}))

vi.mock('../../../entities/knowledge/api/knowledgeApi', () => ({
    getKnowledgeBaseMembers: vi.fn(),
    removeKnowledgeBaseMember: vi.fn(),
    updateKnowledgeBaseMember: vi.fn(),
    addKnowledgeBaseMember: vi.fn(),
    searchKnowledgeMemberCandidates: vi.fn(),
}))

const KB: KnowledgeBase = {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    organizationId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    name: 'Закрытая база',
    description: null,
    visibility: 'MEMBERS',
    enabled: true,
    createdByUserId: auth.userId,
    version: 1,
    createdAt: '2026-10-19T10:00:00Z',
    updatedAt: '2026-10-19T10:00:00Z',
}

function member(level: KnowledgeBaseMember['accessLevel'] = 'OWNER'): KnowledgeBaseMember {
    return {
        knowledgeBaseId: KB.id,
        userId: auth.userId,
        email: 'admin@test.com',
        fullName: 'Demo Admin',
        accessLevel: level,
        version: 7,
        createdAt: '2026-10-19T10:00:00Z',
        updatedAt: '2026-10-19T10:00:00Z',
    }
}

const getMembersMock = vi.mocked(getKnowledgeBaseMembers)
const updateMock = vi.mocked(updateKnowledgeBaseMember)
const removeMock = vi.mocked(removeKnowledgeBaseMember)

function response(current: KnowledgeBaseMember) {
    return { content: [current], page: 0, size: 50, totalElements: 1, totalPages: 1 }
}

function renderModal(onChanged = vi.fn()) {
    render(<KnowledgeMembersModal knowledgeBase={KB} onClose={vi.fn()} onChanged={onChanged} />)
    return onChanged
}

describe('KnowledgeMembersModal / ACL-safe UX', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        auth.userId = '11111111-1111-4111-8111-111111111111'
        getMembersMock.mockResolvedValue(response(member()))
        updateMock.mockResolvedValue(member('VIEWER'))
        removeMock.mockResolvedValue(undefined)
    })

    it('показывает правильную подпись участников и доступное имя select', async () => {
        renderModal()
        expect(await screen.findByText('Все участники показаны')).toBeInTheDocument()
        expect(screen.queryByText('Все базы показаны')).not.toBeInTheDocument()
        expect(screen.getByRole('combobox', {
            name: 'Права в этой базе для admin@test.com',
        })).toHaveValue('OWNER')
    })

    it('требует подтверждения перед снижением собственных OWNER прав', async () => {
        renderModal()
        await screen.findByText('Demo Admin')
        fireEvent.change(screen.getByRole('combobox', {
            name: 'Права в этой базе для admin@test.com',
        }), { target: { value: 'VIEWER' } })

        expect(updateMock).not.toHaveBeenCalled()
        const dialog = await screen.findByRole('dialog', {
            name: 'Подтвердить изменение прав?',
        })
        expect(within(dialog).getByText(/собственную запись участника/)).toBeInTheDocument()
        fireEvent.click(within(dialog).getByRole('button', { name: 'Изменить права' }))
        await waitFor(() => expect(updateMock).toHaveBeenCalledWith(
            KB.id,
            auth.userId,
            { accessLevel: 'VIEWER', expectedVersion: 7 },
        ))
    })

    it('не вызывает backend при отмене изменения OWNER прав', async () => {
        renderModal()
        await screen.findByText('Demo Admin')
        fireEvent.change(screen.getByRole('combobox'), { target: { value: 'EDITOR' } })
        const dialog = await screen.findByRole('dialog', {
            name: 'Подтвердить изменение прав?',
        })
        fireEvent.click(within(dialog).getByRole('button', { name: 'Отмена' }))
        expect(updateMock).not.toHaveBeenCalled()
    })

    it('удаляет доступ только после подтверждения с предупреждением о self/OWNER', async () => {
        renderModal()
        await screen.findByText('Demo Admin')
        fireEvent.click(screen.getByRole('button', { name: 'Удалить доступ' }))
        const dialog = await screen.findByRole('dialog', {
            name: 'Удалить персональный доступ?',
        })
        expect(within(dialog).getByText(/собственную запись участника/)).toBeInTheDocument()
        expect(removeMock).not.toHaveBeenCalled()
        fireEvent.click(within(dialog).getByRole('button', { name: 'Удалить доступ' }))
        await waitFor(() => expect(removeMock).toHaveBeenCalledWith(
            KB.id, auth.userId, 7,
        ))
    })

    it('при отказе API сохраняет запись и предлагает загрузить свежую версию', async () => {
        const existing = member('EDITOR')
        auth.userId = '22222222-2222-4222-8222-222222222222'
        getMembersMock.mockResolvedValue(response(existing))
        updateMock.mockRejectedValueOnce(new Error('Conflict expectedVersion'))
        renderModal()
        await screen.findByText('Demo Admin')
        fireEvent.change(screen.getByRole('combobox'), { target: { value: 'VIEWER' } })
        await screen.findByRole('button', { name: 'Обновить список' })
        expect(screen.getByRole('alert')).toHaveTextContent('Не удалось изменить доступ.')
        expect(screen.getByRole('combobox')).toHaveValue('EDITOR')
        fireEvent.click(screen.getByRole('button', { name: 'Обновить список' }))
        await waitFor(() => expect(getMembersMock).toHaveBeenCalledTimes(2))
    })
    it('блокирует параллельные изменения во время сохранения', async () => {
        const existing = member('VIEWER')
        auth.userId = '22222222-2222-4222-8222-222222222222'
        getMembersMock.mockResolvedValue(response(existing))
        updateMock.mockImplementationOnce(() => new Promise<KnowledgeBaseMember>(() => undefined))
        renderModal()
        await screen.findByText('Demo Admin')
        fireEvent.change(screen.getByRole('combobox'), { target: { value: 'EDITOR' } })
        await waitFor(() => expect(updateMock).toHaveBeenCalledTimes(1))
        expect(screen.getByRole('combobox')).toBeDisabled()
        expect(screen.getByRole('button', { name: 'Удалить доступ' })).toBeDisabled()
        expect(screen.getByRole('status')).toHaveTextContent('Сохранение изменений')
    })
})
