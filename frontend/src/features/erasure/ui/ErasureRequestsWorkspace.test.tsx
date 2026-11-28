// frontend/src/features/erasure/ui/ErasureRequestsWorkspace.test.tsx
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ErasureRequestsWorkspace } from './ErasureRequestsWorkspace'
import * as api from '../api/erasureApi'
import { loadCapabilities } from '../api/workspaceApi'
import { getArchivedChats } from '../../chat/api/chatApi'
import type { ChatSliceResponse, Chat } from '../../chat/api/chatApi'

vi.mock('../api/workspaceApi', () => ({loadCapabilities: vi.fn()}))

vi.mock('../../chat/api/chatApi', () => ({
    getArchivedChats: vi.fn(),
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
        vi.mocked(getArchivedChats).mockResolvedValue({content: [], page: 0, size: 100, first: true, last: true, hasNext: false, hasPrevious: false})
        vi.mocked(loadCapabilities).mockResolvedValue({platform: false, tenantRegistry: false, personalRequests: true, reviewQueue: false, requestsEnabled: true, approvalsEnabled: false})
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
        expect(screen.queryByText('Заявок пока нет.')).not.toBeInTheDocument()
        expect(screen.queryByRole('button', {name: 'Отозвать'})).not.toBeInTheDocument()
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

    it('shows an authorized archived title without loading chat contents or creating a case', async () => {
        const id = '33333333-3333-4333-8333-333333333333'
        const archived: Chat = {id, title: 'Архивный проект', createdAt: '2026-11-01T12:00:00Z', updatedAt: '2026-11-02T12:00:00Z'}
        const page: ChatSliceResponse<Chat> = {content: [archived], page: 0, size: 100, first: true, last: true, hasNext: false, hasPrevious: false}
        vi.mocked(getArchivedChats).mockResolvedValue(page)
        render(<MemoryRouter initialEntries={[{pathname: '/my/erasure-requests', state: {erasureTarget: {type: 'CHAT', id}}}]}><ErasureRequestsWorkspace user={user}/></MemoryRouter>)
        expect(await screen.findByRole('option', {name: archived.title})).toBeInTheDocument()
        expect(screen.getByLabelText('Мой чат')).toHaveValue(id)
        expect(api.previewCase).not.toHaveBeenCalled()
        expect(api.createDraft).not.toHaveBeenCalled()
        expect(api.submitDraft).not.toHaveBeenCalled()
    })

    it.each([false, true])('shows review history only after server capability: %s', async approvalsEnabled => {
        const requestId = '33333333-3333-4333-8333-333333333333'
        vi.mocked(api.loadMine).mockResolvedValue([{requestId, resourceId: user.id, resourceType: 'USER_ACCOUNT', operation: 'DELETE_ACCOUNT', scope: 'PERSONAL_DATA_AND_ACCESS', state: 'SUBMITTED', version: 1, createdAt: '2026-11-01T12:00:00Z', executionStarted: false, completionProven: false}])
        vi.mocked(loadCapabilities).mockResolvedValue({platform: false, tenantRegistry: false, personalRequests: true, reviewQueue: false, requestsEnabled: true, approvalsEnabled})
        render(<MemoryRouter><ErasureRequestsWorkspace user={user}/></MemoryRouter>)
        await screen.findByText('Заявка зарегистрирована')
        if (approvalsEnabled) expect(await screen.findByRole('link', {name: 'История рассмотрения'})).toHaveAttribute('href', `/my/erasure-requests/${requestId}`)
        else {
            expect(screen.queryByRole('link', {name: 'История рассмотрения'})).not.toBeInTheDocument()
            expect(await screen.findByText('Рассмотрение пока недоступно.')).toBeInTheDocument()
        }
        expect(screen.getByRole('button', {name: 'Отозвать'})).toBeEnabled()
    })

    it('does not offer a review link when the capability request fails', async () => {
        const requestId = '33333333-3333-4333-8333-333333333333'
        vi.mocked(api.loadMine).mockResolvedValue([{requestId, resourceId: user.id, resourceType: 'USER_ACCOUNT', operation: 'DELETE_ACCOUNT', scope: 'PERSONAL_DATA_AND_ACCESS', state: 'SUBMITTED', version: 1, createdAt: '2026-11-01T12:00:00Z', executionStarted: false, completionProven: false}])
        vi.mocked(loadCapabilities).mockRejectedValue(new Error('unavailable'))
        render(<MemoryRouter><ErasureRequestsWorkspace user={user}/></MemoryRouter>)
        await screen.findByText('Доступ к рассмотрению не подтверждён. Обновите список.')
        expect(screen.queryByRole('link', {name: 'История рассмотрения'})).not.toBeInTheDocument()
        expect(screen.getByRole('button', {name: 'Отозвать'})).toBeEnabled()
    })

})
