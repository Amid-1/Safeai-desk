// frontend/src/pages/ErasureReviewCenterPage.test.tsx
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import ErasureReviewCenterPage from './ErasureReviewCenterPage'
import { loadReviewSummary } from '../features/erasure/review/reviewApi'

vi.mock('../features/erasure/review/reviewApi', () => ({ loadReviewSummary: vi.fn() }))

describe('R2 platform overview is truthful without dashboard access', () => {
    beforeEach(() => { vi.resetAllMocks() })

    it('shows server numbers only after a successful response', async () => {
        vi.mocked(loadReviewSummary).mockResolvedValue({
            total: 18, pending: 6, needsInformation: 2, held: 1,
        })
        render(<MemoryRouter><ErasureReviewCenterPage /></MemoryRouter>)
        expect(await screen.findByText('18')).toBeInTheDocument()
        expect(screen.getByText('6')).toBeInTheDocument()
        expect(screen.getByText('2')).toBeInTheDocument()
        expect(screen.getByText('1')).toBeInTheDocument()
        expect(screen.getByRole('link', { name: 'Политика хранения' })).toHaveAttribute('href', '/admin/retention')
    })

    it('retains policy navigation and never invents zero metrics on denied access', async () => {
        vi.mocked(loadReviewSummary).mockRejectedValue(new Error('Forbidden'))
        render(<MemoryRouter><ErasureReviewCenterPage /></MemoryRouter>)
        expect(await screen.findByRole('alert')).toHaveTextContent('Сводка недоступна')
        expect(screen.getByRole('link', { name: 'Политика хранения' })).toHaveAttribute('href', '/admin/retention')
        expect(screen.getByText('Удаление отключено')).toBeInTheDocument()
        expect(screen.queryByText('100%')).not.toBeInTheDocument()
    })
})
