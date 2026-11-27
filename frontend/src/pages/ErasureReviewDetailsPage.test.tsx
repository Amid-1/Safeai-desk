// frontend/src/pages/ErasureReviewDetailsPage.test.tsx
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, Link } from 'react-router-dom'
import ErasureReviewDetailsPage from './ErasureReviewDetailsPage'
import * as api from '../features/erasure/review/reviewApi'

const auth = vi.hoisted(() => ({ id: 'cf310a14-6110-49ac-8bb0-037a43e12271' }))
vi.mock('../features/auth/state/useAuth', () => ({
    useAuth: () => ({ currentUser: { id: auth.id } }),
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
function mount(reviewer = true) {
    const base = reviewer ? '/admin/erasure/requests' : '/my/erasure-requests'
    return render(<MemoryRouter initialEntries={[`${base}/${entry.requestId}`]}>
        <Routes><Route path={`${base}/:requestId`}
            element={<ErasureReviewDetailsPage reviewer={reviewer} />} /></Routes>
    </MemoryRouter>)
}

describe('R2 review decisions', () => {
    beforeEach(() => {
        vi.resetAllMocks()
        auth.id = 'cf310a14-6110-49ac-8bb0-037a43e12271'
        vi.mocked(api.loadMyReview).mockResolvedValue(entry)
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

    it.each(['PENDING', 'NEEDS_INFORMATION', 'APPROVED_REVIEW_ONLY'] as const)(
        'allows LEGAL hold for %s without granting REVIEW', async reviewState => {
            vi.mocked(api.loadReview).mockResolvedValue({ ...entry, reviewState })
            vi.mocked(api.loadReviewAccess).mockResolvedValue({ reviewer: false, legal: true })
            mount()
            expect(await screen.findByRole('button', { name: 'Установить удержание' })).toBeInTheDocument()
            expect(screen.queryByRole('button', { name: 'Сохранить решение' })).not.toBeInTheDocument()
        })

    it.each(['REJECTED', 'WITHDRAWN'] as const)('does not allow LEGAL hold in %s', async reviewState => {
        vi.mocked(api.loadReview).mockResolvedValue({ ...entry, reviewState })
        vi.mocked(api.loadReviewAccess).mockResolvedValue({ reviewer: true, legal: true })
        mount()
        await screen.findByText('Сведения о заявке')
        expect(screen.queryByRole('button', { name: /Установить удержание|Снять удержание|Сохранить решение/ }))
            .not.toBeInTheDocument()
    })

    it('does not allow a reviewer or legal actor to act on their own request', async () => {
        auth.id = entry.requesterUserId
        vi.mocked(api.loadReviewAccess).mockResolvedValue({ reviewer: true, legal: true })
        mount()
        await screen.findByText('Сведения о заявке')
        expect(screen.queryByRole('button', { name: /Установить удержание|Снять удержание|Сохранить решение/ }))
            .not.toBeInTheDocument()
    })

    it('offers independent legal release, not a review decision, during a hold', async () => {
        vi.mocked(api.loadReview).mockResolvedValue({ ...entry, holdActive: true })
        vi.mocked(api.loadReviewAccess).mockResolvedValue({ reviewer: true, legal: true })
        mount()
        expect(await screen.findByRole('button', { name: 'Снять удержание' })).toBeInTheDocument()
        expect(screen.queryByRole('button', { name: 'Сохранить решение' })).not.toBeInTheDocument()
    })

    it.each([true, false])('only permits owner clarification when holdActive=%s is false', async holdActive => {
        auth.id = entry.requesterUserId
        vi.mocked(api.loadMyReview).mockResolvedValue({ ...entry, reviewState: 'NEEDS_INFORMATION', holdActive })
        mount(false)
        await screen.findByText('Сведения о заявке')
        const button = screen.queryByRole('button', { name: 'Вернуть на рассмотрение' })
        if (holdActive) expect(button).not.toBeInTheDocument()
        else expect(button).toBeInTheDocument()
    })

    it('does not display a response for a different requester', async () => {
        vi.mocked(api.loadMyReview).mockResolvedValue({ ...entry, reviewState: 'NEEDS_INFORMATION' })
        mount(false)
        await screen.findByRole('button', { name: 'Повторить' })
        expect(screen.queryByText('Сведения о заявке')).not.toBeInTheDocument()
        expect(screen.queryByRole('button', { name: 'Вернуть на рассмотрение' })).not.toBeInTheDocument()
    })

    it('rejects a response for a different request ID before displaying any controls', async () => {
        vi.mocked(api.loadReview).mockResolvedValue({ ...entry, requestId: '00000000-0000-4000-8000-000000000001' })
        mount()
        await screen.findByRole('button', { name: 'Повторить' })
        expect(screen.queryByText('Сведения о заявке')).not.toBeInTheDocument()
        expect(screen.queryByRole('button', { name: 'Сохранить решение' })).not.toBeInTheDocument()
    })


    it('does not reuse a late decision response after navigating to another case', async () => {
        const other = { ...entry, requestId: '00000000-0000-4000-8000-000000000002' }
        vi.mocked(api.loadReview).mockImplementation(async id => id === entry.requestId ? entry : other)
        let resolveDecision!: (value: api.ReviewView) => void
        vi.mocked(api.reviewDecision).mockImplementation(() => new Promise(resolve => { resolveDecision = resolve }))
        const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
        try {
            render(<MemoryRouter initialEntries={[`/admin/erasure/requests/${entry.requestId}`]}>
                <Link to={`/admin/erasure/requests/${other.requestId}`}>Другая заявка</Link>
                <Routes><Route path="/admin/erasure/requests/:requestId"
                    element={<ErasureReviewDetailsPage reviewer />} /></Routes>
            </MemoryRouter>)
            fireEvent.click(await screen.findByRole('button', { name: 'Сохранить решение' }))
            expect(api.reviewDecision).toHaveBeenCalledTimes(1)
            fireEvent.click(screen.getByRole('link', { name: 'Другая заявка' }))
            await screen.findByRole('button', { name: 'Сохранить решение' })
            const calls = vi.mocked(api.loadReview).mock.calls.length
            await act(async () => resolveDecision({ ...entry, reviewState: 'NEEDS_INFORMATION', version: 2 }))
            expect(api.loadReview).toHaveBeenCalledTimes(calls)
            expect(screen.queryByText('Действие зарегистрировано. Физическое удаление не начиналось.')).not.toBeInTheDocument()
            expect(screen.getByRole('button', { name: 'Сохранить решение' })).toBeEnabled()
            expect(screen.getByText(`Заявка: ${other.requestId}`)).toBeInTheDocument()
            expect(screen.queryByText(`Заявка: ${entry.requestId}`)).not.toBeInTheDocument()
        } finally { confirm.mockRestore() }
    })

})
