// frontend/src/pages/PrivacySettingsPage.test.tsx
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import PrivacySettingsPage from './PrivacySettingsPage'

const auth = vi.hoisted(() => ({ currentUser: { enabled: true, roles: ['SUPER_ADMIN'] } }))
vi.mock('../features/auth/state/useAuth', () => ({ useAuth: () => ({ currentUser: auth.currentUser }) }))

describe('R2 privacy route role UX', () => {
    it('does not offer an inaccessible self-request route to platform super admin', () => {
        render(<MemoryRouter><PrivacySettingsPage /></MemoryRouter>)
        expect(screen.getByText(/Для системного супер-администратора/)).toBeInTheDocument()
        expect(screen.queryByRole('link', { name: /Создать заявку/ })).not.toBeInTheDocument()
        expect(screen.queryByRole('link', { name: /Перейти к запросам/ })).not.toBeInTheDocument()
    })
})
