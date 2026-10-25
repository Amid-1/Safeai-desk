// frontend/src/features/knowledge/ui/KnowledgeMembersModal.production.test.tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { KnowledgeBase, KnowledgeBaseMember } from '../../../entities/knowledge/api/knowledgeApi'
import {
    getKnowledgeBaseMembers, searchKnowledgeMemberCandidates, addKnowledgeBaseMember,
    updateKnowledgeBaseMember, removeKnowledgeBaseMember,
} from '../../../entities/knowledge/api/knowledgeApi'
import KnowledgeMembersModal from './KnowledgeMembersModal'

vi.mock('../../auth/state/useAuth', () => ({ useAuth: () => ({ currentUser: { id: '11111111-1111-4111-8111-111111111111', roles: ['ADMIN'] } }) }))
vi.mock('../../../entities/knowledge/api/knowledgeApi', () => ({
    getKnowledgeBaseMembers: vi.fn(), searchKnowledgeMemberCandidates: vi.fn(),
    addKnowledgeBaseMember: vi.fn(), updateKnowledgeBaseMember: vi.fn(),
    removeKnowledgeBaseMember: vi.fn(),
}))
const kb: KnowledgeBase = {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    organizationId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    name: 'Runbooks', description: null, visibility: 'MEMBERS', enabled: true,
    createdByUserId: '11111111-1111-4111-8111-111111111111', version: 1,
    createdAt: '2026-10-19T10:00:00Z', updatedAt: '2026-10-19T10:00:00Z',
}
const owner: KnowledgeBaseMember = {
    knowledgeBaseId: kb.id, userId: '11111111-1111-4111-8111-111111111111',
    email: 'owner@example.com', fullName: 'Владелец', accessLevel: 'OWNER', version: 7,
    createdAt: '2026-10-19T10:00:00Z', updatedAt: '2026-10-19T10:00:00Z',
}
const getMembers = vi.mocked(getKnowledgeBaseMembers)
const searchCandidates = vi.mocked(searchKnowledgeMemberCandidates)
const addMember = vi.mocked(addKnowledgeBaseMember)
const updateMember = vi.mocked(updateKnowledgeBaseMember)
const removeMember = vi.mocked(removeKnowledgeBaseMember)
function response(items: KnowledgeBaseMember[] = [owner]) {
    return { content: items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0 }
}
function deferred<T>() {
    let resolve!: (value: T) => void
    let reject!: (reason: unknown) => void
    const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
    return { promise, resolve, reject }
}

describe('KnowledgeMembersModal — конкурентность и ACL', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        getMembers.mockResolvedValue(response())
        searchCandidates.mockResolvedValue([])
        addMember.mockResolvedValue(owner)
        updateMember.mockResolvedValue({ ...owner, accessLevel: 'VIEWER', version: 8 })
        removeMember.mockResolvedValue(undefined)
    })

    it('не принимает опоздавший результат старого поиска кандидатов', async () => {
        const old = deferred<Awaited<ReturnType<typeof searchKnowledgeMemberCandidates>>>()
        searchCandidates.mockReturnValueOnce(old.promise).mockResolvedValueOnce([
            { userId: '33333333-3333-4333-8333-333333333333', email: 'new@example.com', fullName: 'Актуальный' },
        ])
        render(<KnowledgeMembersModal knowledgeBase={kb} onClose={vi.fn()} onChanged={vi.fn()} />)
        await screen.findByText('Владелец')
        const input = screen.getByRole('textbox', { name: 'Имя или email сотрудника' })
        fireEvent.change(input, { target: { value: 'old' } })
        fireEvent.click(screen.getByRole('button', { name: 'Найти' }))
        fireEvent.change(input, { target: { value: 'new' } })
        // Повторный поиск запускается Enter даже при ожидании предыдущего ответа.
        fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' })
        await screen.findByText('Актуальный')
        old.resolve([{ userId: '44444444-4444-4444-8444-444444444444', email: 'old@example.com', fullName: 'Устаревший' }])
        await waitFor(() => expect(screen.queryByText('Устаревший')).not.toBeInTheDocument())
        expect(searchCandidates).toHaveBeenCalledTimes(2)
    })

    it('не отображает ответ ACL-запроса после отмены при смене базы', async () => {
        const old = deferred<ReturnType<typeof response>>()
        getMembers.mockReturnValueOnce(old.promise)
        getMembers.mockResolvedValueOnce(response([]))
        const view = render(<KnowledgeMembersModal knowledgeBase={kb} onClose={vi.fn()} onChanged={vi.fn()} />)
        const secondKb = { ...kb, id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', name: 'Другая база' }
        view.rerender(<KnowledgeMembersModal knowledgeBase={secondKb} onClose={vi.fn()} onChanged={vi.fn()} />)
        await screen.findByText('Явных участников нет.')
        old.resolve(response())
        await waitFor(() => expect(screen.queryByText('Владелец')).not.toBeInTheDocument())
        expect(getMembers).toHaveBeenCalledTimes(2)
    })

    it('отправляет добавление с VIEWER, не назначая повышенные права', async () => {
        const candidate = { userId: '33333333-3333-4333-8333-333333333333', email: 'new@example.com', fullName: 'Кандидат' }
        searchCandidates.mockResolvedValueOnce([candidate])
        const onChanged = vi.fn()
        render(<KnowledgeMembersModal knowledgeBase={kb} onClose={vi.fn()} onChanged={onChanged} />)
        await screen.findByText('Владелец')
        fireEvent.change(screen.getByRole('textbox', { name: 'Имя или email сотрудника' }), { target: { value: 'new@' } })
        fireEvent.click(screen.getByRole('button', { name: 'Найти' }))
        await screen.findByText('Кандидат')
        fireEvent.click(screen.getByRole('button', { name: 'Добавить для просмотра' }))
        await waitFor(() => expect(addMember).toHaveBeenCalledWith(kb.id, { userId: candidate.userId, accessLevel: 'VIEWER' }))
        await waitFor(() => expect(onChanged).toHaveBeenCalledOnce())
    })

    it('при неудачном PATCH не вызывает onChanged и не удаляет текущую ACL-строку', async () => {
        updateMember.mockRejectedValueOnce(new Error('Conflict expectedVersion'))
        const onChanged = vi.fn()
        render(<KnowledgeMembersModal knowledgeBase={kb} onClose={vi.fn()} onChanged={onChanged} />)
        await screen.findByText('Владелец')
        fireEvent.change(screen.getByRole('combobox', { name: 'Права в этой базе для owner@example.com' }), { target: { value: 'VIEWER' } })
        expect(updateMember).not.toHaveBeenCalled()
        fireEvent.click(screen.getByRole('button', { name: 'Изменить права' }))
        await waitFor(() => expect(screen.getByText('Не удалось изменить доступ.'))
            .toBeInTheDocument())
        expect(updateMember).toHaveBeenCalledWith(kb.id, owner.userId, {
            accessLevel: 'VIEWER',
            expectedVersion: owner.version,
        })
        expect(onChanged).not.toHaveBeenCalled()
        // Ошибка оставляет ConfirmDialog открытым; он делает исходный диалог inert.
        // Закрываем подтверждение, затем проверяем исходное значение ACL.
        fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))
        expect(screen.getByRole('combobox', { name: 'Права в этой базе для owner@example.com' })).toHaveValue('OWNER')
    })

    it('при отправке DELETE передаёт серверную версию записи', async () => {
        render(<KnowledgeMembersModal knowledgeBase={kb} onClose={vi.fn()} onChanged={vi.fn()} />)
        await screen.findByText('Владелец')
        fireEvent.click(screen.getByRole('button', { name: 'Удалить доступ' }))
        expect(removeMember).not.toHaveBeenCalled()
        fireEvent.click(screen.getAllByRole('button', { name: 'Удалить доступ' }).at(-1)!)
        await waitFor(() => expect(removeMember).toHaveBeenCalledWith(kb.id, owner.userId, 7))
    })
})
