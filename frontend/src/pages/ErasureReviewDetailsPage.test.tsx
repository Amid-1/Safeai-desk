// frontend/src/pages/ErasureReviewDetailsPage.test.tsx
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import ErasureReviewDetailsPage from './ErasureReviewDetailsPage'
import * as api from '../features/erasure/review/reviewApi'

vi.mock('../features/auth/state/useAuth', () => ({
    useAuth: () => ({ currentUser: { id: 'cf310a14-6110-49ac-8bb0-037a43e12271' } }),
}))
vi.mock('../features/erasure/review/reviewApi', () => ({
    loadReview: vi.fn(), loadMyReview: vi.fn(), loadReviewAccess: vi.fn(),
    loadReviewHistory: vi.fn(), reviewDecision: vi.fn(), applyLegalHold: vi.fn(),
    releaseLegalHold: vi.fn(), provideReviewInformation: vi.fn(),
}))
const entry: api.ReviewView = {
    requestId: '4cc9a902-47a2-46f7-9594-49261e6dd90e',
    requesterUserId: '1f45e10d-05fc-45b5-ab1c-f57d4be992fd',
    resourceType: 'CHAT', resourceId: 'acc438fd-d1c8-47a9-82b6-49c0ee50e8b8',
    operation: 'REDACT_CONTENT', scope: 'FULL_CHAT_CONTENT', baseState: 'SUBMITTED',
    reviewState: 'PENDING', version: 1, holdActive: false, reasonCode: null,
    createdAt: '2026-11-25T00:00:00Z', updatedAt: '2026-11-25T00:00:00Z',
    executionAvailable: false, completionProven: false,
}
function mount() {
    return render(<MemoryRouter initialEntries={[`/admin/erasure/requests/${entry.requestId}`]}>
        <Routes><Route path="/admin/erasure/requests/:requestId"
            element={<ErasureReviewDetailsPage reviewer />} /></Routes>
    </MemoryRouter>)
}

describe('R2 review decisions', () => {
    beforeEach(() => {
        vi.resetAllMocks()
        vi.mocked(api.loadReview).mockResolvedValue(entry)
        vi.mocked(api.loadReviewHistory).mockResolvedValue([])
        vi.mocked(api.loadReviewAccess).mockResolvedValue({ reviewer: true, legal: false })
    })

    it('does not show physical deletion even for independent reviewer', async () => {
        mount()
        expect(await screen.findByText('Решение проверяющего')).toBeInTheDocument()
        expect(screen.getAllByText('Исполнение').length).toBeGreaterThan(0)
        expect(screen.getByText('Не подключено')).toBeInTheDocument()
        expect(screen.queryByRole('button', { name: /Удалить навсегда|Запустить удаление/ }))
            .not.toBeInTheDocument()
    })

    it('changes reason options when reviewer switches to preliminary approval', async () => {
        mount()
        expect(await screen.findByText('Решение проверяющего')).toBeInTheDocument()
        fireEvent.click(screen.getByRole('radio', { name: /Предварительно согласовать/ }))
        await waitFor(() => expect(screen.getByRole('option', { name: 'Запрос владельца подтверждён' }))
            .toBeInTheDocument())
        expect(screen.queryByRole('option', { name: 'Недостаточно сведений' })).not.toBeInTheDocument()
    })

    it('removes previously granted controls after a failed reload', async () => {
        mount()
        expect(await screen.findByText('Решение проверяющего')).toBeInTheDocument()
        vi.mocked(api.loadReviewAccess).mockRejectedValue(new Error('Forbidden'))
        fireEvent.click(screen.getByRole('button', { name: 'Обновить карточку' }))
        await screen.findByRole('button', { name: 'Повторить' })
        expect(screen.queryByText('Решение проверяющего')).not.toBeInTheDocument()
        expect(screen.queryByText('Сведения о заявке')).not.toBeInTheDocument()
    })

    it('never offers a decision when an active hold exists', async () => {
        vi.mocked(api.loadReview).mockResolvedValue({ ...entry, holdActive: true })
        mount()
        expect(await screen.findByText('Сведения о заявке')).toBeInTheDocument()
        expect(screen.queryByText('Решение проверяющего')).not.toBeInTheDocument()
    })
})
