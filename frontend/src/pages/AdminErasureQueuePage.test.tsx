import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import AdminErasureQueuePage from './AdminErasureQueuePage'
import * as api from '../features/erasure/api/erasureApi'

vi.mock('../features/erasure/api/erasureApi', () => ({
    loadTenantQueue: vi.fn(),
}))

describe('R1a administrator erasure queue', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        vi.mocked(api.loadTenantQueue).mockResolvedValue([])
    })

    it('does not display approval or physical deletion buttons', async () => {
        render(<AdminErasureQueuePage />)
        expect(await screen.findByText('Заявок нет.')).toBeInTheDocument()
        expect(screen.getByText(/Только просмотр/)).toBeInTheDocument()
        expect(screen.queryByRole('button', { name: /Удалить|Согласовать|Одобрить/ }))
            .not.toBeInTheDocument()
    })

    it('renders human status labels while keeping raw ids under details', async () => {
        vi.mocked(api.loadTenantQueue).mockResolvedValue([{
            requestId: 'd4b3931d-c539-4612-9ced-ef67bc860e6c',
            resourceType: 'KNOWLEDGE_DOCUMENT',
            resourceId: 'a707e23c-967d-40b9-901f-4590b6a21b3c',
            operation: 'DELETE_OBJECT', scope: 'FULL_DOCUMENT',
            state: 'SUBMITTED', version: 1,
            createdAt: '2026-11-24T12:00:00Z',
            executionStarted: false, completionProven: false,
        }])
        render(<AdminErasureQueuePage />)
        expect(await screen.findByText('Документ')).toBeInTheDocument()
        expect(screen.getByText('Заявка подана')).toBeInTheDocument()
        expect(screen.getByText('Технические сведения о заявке')).toBeInTheDocument()
        expect(screen.queryByRole('button', { name: /Удалить|Согласовать/ }))
            .not.toBeInTheDocument()
    })
})
