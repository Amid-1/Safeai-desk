// frontend/src/features/erasure/ui/ErasureRequestsWorkspace.test.tsx
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ErasureRequestsWorkspace } from './ErasureRequestsWorkspace'
import * as api from '../api/erasureApi'

vi.mock('../../chat/api/chatApi', () => ({
    getChats: vi.fn(async () => ({ content: [
        { id: 'a8517423-018f-412b-95a2-9c79748bf19a', title: 'Рабочий чат' },
    ] })),
}))
vi.mock('../../../entities/knowledge/api/knowledgeApi', () => ({
    getKnowledgeBases: vi.fn(async () => ({ content: [] })),
}))
vi.mock('../../../entities/knowledge/api/knowledgeDocumentApi', () => ({
    getKnowledgeDocuments: vi.fn(async () => ({ content: [] })),
}))

vi.mock('../api/erasureApi', () => ({
    loadMine: vi.fn(), loadTenantQueue: vi.fn(), previewCase: vi.fn(),
    createDraft: vi.fn(), submitDraft: vi.fn(), withdrawCase: vi.fn(),
}))
const user = {
    id: 'c8a26ea0-3fd9-45e6-a4b8-d7f9f83b4c54',
    organizationId: '83155da1-4fae-4629-abde-9e09aa500b13',
    roles: ['USER'],
}

describe('R1a user interface', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        vi.mocked(api.loadMine).mockResolvedValue([])
    })

    it('clearly refuses to promise physical deletion', async () => {
        render(<MemoryRouter><ErasureRequestsWorkspace user={user} /></MemoryRouter>)
        expect(await screen.findByText('Заявок пока нет.')).toBeInTheDocument()
        expect(screen.getByText(/Исполнение пока не включено/)).toBeInTheDocument()
        expect(screen.getByText(/не будут физически удалены/)).toBeInTheDocument()
        expect(screen.queryByText('Удалить навсегда')).not.toBeInTheDocument()
    })

    it('does not offer tenant offboarding to ordinary users', async () => {
        render(<MemoryRouter><ErasureRequestsWorkspace user={user} /></MemoryRouter>)
        await screen.findByText('Заявок пока нет.')
        expect(screen.queryByRole('option', { name: 'Моя организация' })).not.toBeInTheDocument()
    })

    it('disables preview when document id is missing', async () => {
        render(<MemoryRouter><ErasureRequestsWorkspace user={user} /></MemoryRouter>)
        await screen.findByText('Заявок пока нет.')
        fireEvent.change(screen.getByLabelText('Что вы хотите удалить'),
            { target: { value: 'KNOWLEDGE_DOCUMENT' } })
        expect(screen.getByRole('button', { name: 'Проверить запрос' })).toBeDisabled()
    })

    it('shows an error instead of claiming success when loading fails', async () => {
        vi.mocked(api.loadMine).mockRejectedValue(new Error('connection failed'))
        render(<MemoryRouter><ErasureRequestsWorkspace user={user} /></MemoryRouter>)
        await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
        expect(screen.queryByText('Заявка зарегистрирована.')).not.toBeInTheDocument()
    })

    it('does not expose submit or withdraw actions without a case', async () => {
        render(<MemoryRouter><ErasureRequestsWorkspace user={user} /></MemoryRouter>)
        await screen.findByText('Заявок пока нет.')
        expect(screen.queryByRole('button', { name: 'Подать заявку' })).not.toBeInTheDocument()
        expect(screen.queryByRole('button', { name: 'Отозвать' })).not.toBeInTheDocument()
    })

    it('offers only server-returned chat choices, never manual UUID input', async () => {
        render(<MemoryRouter><ErasureRequestsWorkspace user={user} /></MemoryRouter>)
        await screen.findByText('Заявок пока нет.')
        fireEvent.change(screen.getByLabelText('Что вы хотите удалить'),
            { target: { value: 'CHAT' } })
        expect(await screen.findByRole('option', { name: 'Рабочий чат' })).toBeInTheDocument()
        expect(screen.queryByLabelText('UUID')).not.toBeInTheDocument()
        expect(screen.getByRole('button', { name: 'Проверить запрос' })).toBeDisabled()
        fireEvent.change(screen.getByLabelText('Мой чат'),
            { target: { value: 'a8517423-018f-412b-95a2-9c79748bf19a' } })
        expect(screen.getByRole('button', { name: 'Проверить запрос' })).toBeEnabled()
    })

    it('creates a preview only for an authorized server response', async () => {
        vi.mocked(api.previewCase).mockResolvedValue({
            previewId: '2ff03c9c-44af-4c8a-abd9-8de2402b6d7b',
            previewDigest: 'a'.repeat(64), expectedResourceVersion: 0,
            expiresAt: '2030-01-01T12:00:00Z', executionAvailable: false,
        })
        render(<MemoryRouter><ErasureRequestsWorkspace user={user} /></MemoryRouter>)
        await screen.findByText('Заявок пока нет.')
        fireEvent.click(screen.getByRole('button', { name: 'Проверить запрос' }))
        expect(await screen.findByText(/Предпросмотр готов/)).toBeInTheDocument()
        expect(screen.getByRole('button', { name: 'Создать черновик' })).toBeEnabled()
        expect(vi.mocked(api.createDraft)).not.toHaveBeenCalled()
    })
    it('preserves a contextual archived chat absent from the active list without auto-submission', async () => {
        const id = '33333333-3333-4333-8333-333333333333'
        render(<MemoryRouter initialEntries={[{ pathname: '/my/erasure-requests', state: { erasureTarget: { type: 'CHAT', id }, from: '/chat' } }]}>
            <ErasureRequestsWorkspace user={user} />
        </MemoryRouter>)
        await screen.findByRole('option', { name: 'Рабочий чат' })
        expect(screen.getByRole('option', { name: 'Чат из карточки' })).toBeInTheDocument()
        expect(screen.getByLabelText('Мой чат')).toHaveValue(id)
        expect(api.previewCase).not.toHaveBeenCalled()
        expect(api.createDraft).not.toHaveBeenCalled()
        expect(api.submitDraft).not.toHaveBeenCalled()
    })
    it('prefills a contextual chat but does not submit, preview or create automatically', async () => {
        const id = 'a8517423-018f-412b-95a2-9c79748bf19a'
        render(<MemoryRouter initialEntries={[{ pathname: '/my/erasure-requests', state: { erasureTarget: { type: 'CHAT', id } } }]}>
            <ErasureRequestsWorkspace user={user} />
        </MemoryRouter>)
        await screen.findByRole('option', { name: 'Рабочий чат' })
        expect(screen.getByLabelText('Мой чат')).toHaveValue(id)
        expect(api.previewCase).not.toHaveBeenCalled()
        expect(api.createDraft).not.toHaveBeenCalled()
        expect(api.submitDraft).not.toHaveBeenCalled()
    })

})
