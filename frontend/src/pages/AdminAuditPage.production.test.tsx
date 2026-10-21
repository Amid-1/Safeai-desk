// frontend/src/pages/AdminAuditPage.production.test.tsx
// ============================================================
// frontend/src/pages/AdminAuditPage.production.test.tsx
// Изоляция загруженного журнала при смене субъекта и прав доступа.
// ============================================================
import {fireEvent, render, screen} from '@testing-library/react'
import type {ReactNode} from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {useAuth} from '../features/auth/state/useAuth'
import type {AuthContextValue} from '../features/auth/state/authContext.definition'
import AdminAuditPage from './AdminAuditPage'

vi.mock('../features/auth/state/useAuth', () => ({ useAuth: vi.fn() }))
vi.mock('../features/audit/controller/useAdminAuditController', () => ({
    useAdminAuditController: vi.fn(() => ({})),
}))
vi.mock('../features/audit/ui/AdminAuditPageView', () => {
    // Типизированная подмена именованного экспорта настоящего модуля.
    // Vitest использует экспорт динамически, поэтому инспекция IntelliJ
    // ошибочно считает одноимённое свойство неиспользуемым.
    return {
        // noinspection JSUnusedGlobalSymbols
        AdminAuditPageView: () => (
            <input
                aria-label="Временный маркер выбранной записи"
                defaultValue=""
            />
        ),
    } satisfies Pick<
        typeof import('../features/audit/ui/AdminAuditPageView'),
        'AdminAuditPageView'
    >
})
vi.mock('../shared/ui/PageErrorBoundary', () => ({
    default: ({ children }: { children: ReactNode }) => <>{children}</>,
}))

const mockAuth = vi.mocked(useAuth)
const TENANT_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

function setIdentity(id: string, isSuperAdmin: boolean, org = TENANT_ID) {
    mockAuth.mockReturnValue({
        currentUser: {
            id, organizationId: org, email: 'test@example.com',
            fullName: null, enabled: true,
            roles: isSuperAdmin ? ['SUPER_ADMIN'] : ['ADMIN'],
        },
        authStatus: 'authenticated', authLoading: false,
        authError: null, loginUser: vi.fn(), logoutUser: vi.fn(),
        reloadCurrentUser: vi.fn(),
    } satisfies AuthContextValue)
}

beforeEach(() => {
    vi.clearAllMocks()
    setIdentity('11111111-1111-4111-8111-111111111111', true)
})

describe('AdminAuditPage — границы контекста доступа', () => {
    it('сбрасывает внутреннее состояние просмотра после отзыва SUPER_ADMIN', () => {
        const { rerender } = render(<AdminAuditPage />)
        const field = screen.getByRole('textbox', { name: 'Временный маркер выбранной записи' })
        fireEvent.change(field, { target: { value: 'чужое событие' } })
        setIdentity('11111111-1111-4111-8111-111111111111', false)
        rerender(<AdminAuditPage />)
        const fresh = screen.getByRole('textbox', { name: 'Временный маркер выбранной записи' })
        expect(fresh).not.toBe(field)
        expect(fresh).toHaveValue('')
    })

    it('изолирует просмотр при переключении пользователя', () => {
        const { rerender } = render(<AdminAuditPage />)
        const oldField = screen.getByRole('textbox')
        setIdentity('22222222-2222-4222-8222-222222222222', true)
        rerender(<AdminAuditPage />)
        expect(screen.getByRole('textbox')).not.toBe(oldField)
    })

    it('изолирует просмотр при смене организации с сохранением роли', () => {
        const { rerender } = render(<AdminAuditPage />)
        const oldField = screen.getByRole('textbox')
        setIdentity('11111111-1111-4111-8111-111111111111', true,
            'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
        rerender(<AdminAuditPage />)
        expect(screen.getByRole('textbox')).not.toBe(oldField)
    })

    it('не сбрасывает просмотр при обычном повторном рендере с теми же правами', () => {
        const { rerender } = render(<AdminAuditPage />)
        const field = screen.getByRole('textbox')
        rerender(<AdminAuditPage />)
        expect(screen.getByRole('textbox')).toBe(field)
    })
})
