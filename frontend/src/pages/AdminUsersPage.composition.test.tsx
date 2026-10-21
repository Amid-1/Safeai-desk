// frontend/src/pages/AdminUsersPage.composition.test.tsx
// Проверяет сохранение полномочий и контрактов колбэков при переносе композиции страницы.
import { fireEvent, render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import type { AuthUser } from '../features/auth/api/authApi'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminUsersPage from './AdminUsersPage'

const mocks = vi.hoisted(() => ({
    directory: vi.fn(),
    create: vi.fn(),
    mutations: vi.fn(),
    view: vi.fn(),
    dialogs: vi.fn(),
}))

vi.mock('../shared/ui/PageErrorBoundary', () => ({
    default: ({ children }: { children: ReactNode }) => <>{children}</>,
}))
vi.mock('../features/admin-users/lib/useAdminUsersDirectory', () => ({
    useAdminUsersDirectory: mocks.directory,
}))
vi.mock('../features/admin-users/lib/useAdminUserCreate', () => ({
    useAdminUserCreate: mocks.create,
}))
vi.mock('../features/admin-users/lib/useAdminUserMutations', () => ({
    useAdminUserMutations: mocks.mutations,
}))
vi.mock('../features/admin-users/ui/AdminUsersView', () => ({
    AdminUsersView: (props: {
        onReload: () => void
        currentUserIsSuperAdmin: boolean
        onCreate: () => void
    }) => {
        mocks.view(props)
        return (
            <div>
                <button type="button" onClick={props.onReload}>Обновить список</button>
                <button type="button" onClick={props.onCreate}>Создать пользователя</button>
            </div>
        )
    },
}))
vi.mock('../features/admin-users/ui/AdminUsersDialogs', () => ({
    AdminUsersDialogs: (props: { currentUserIsSuperAdmin: boolean }) => {
        mocks.dialogs(props)
        return null
    },
}))

const CURRENT_USER: AuthUser = {
    id: '11111111-1111-4111-8111-111111111111',
    organizationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    email: 'admin@example.test',
    fullName: null,
    enabled: true,
    roles: ['ADMIN'],
}

const reload = vi.fn()
const createOpen = vi.fn()
const mutationsPermissions = vi.fn(() => false)
const organizations = [{ id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', name: 'Demo' }]
const requestReloadFromFirstPage = vi.fn()

beforeEach(() => {
    vi.clearAllMocks()
    mocks.directory.mockReturnValue({
        users: [],
        statistics: { total: 0, administrators: 0, users: 0, enabled: 0, disabled: 0 },
        organizations,
        organizationsError: '',
        organizationsLoading: false,
        page: 0, totalPages: 0, loading: false, loadError: '',
        filter: 'ALL', status: 'ALL', searchDraft: '', appliedSearch: '', totalMatched: 0,
        detailsLoadingUserId: null,
        reload,
        requestReloadFromFirstPage,
    })
    mocks.create.mockReturnValue({
        creating: false,
        success: 'Создание завершено.',
        createModalOpen: false,
        openCreateModal: createOpen,
    })
    mocks.mutations.mockReturnValue({
        mutationError: '', success: '', hasPendingMutation: false,
        canManageUser: mutationsPermissions,
        canPermanentlyDelete: mutationsPermissions,
    })
})

describe('AdminUsersPage: изоляция маршрута от операций пользователей', () => {
    it.each([
        ['ADMIN', false],
        ['SUPER_ADMIN', true],
    ] as const)('передаёт полномочия %s в UI и оба контроллера', (role, isSuperAdmin) => {
        const actor = { ...CURRENT_USER, roles: [role] }
        render(<AdminUsersPage currentUser={actor} />)
        expect(mocks.directory).toHaveBeenCalledWith(actor, isSuperAdmin)
        expect(mocks.create).toHaveBeenCalledWith(expect.objectContaining({
            currentUser: actor,
            currentUserIsSuperAdmin: isSuperAdmin,
            organizations,
            requestReloadFromFirstPage,
        }))
        expect(mocks.mutations).toHaveBeenCalledWith(expect.objectContaining({
            currentUser: actor,
            currentUserIsSuperAdmin: isSuperAdmin,
            organizations,
            requestReloadFromFirstPage,
        }))
        expect(mocks.view).toHaveBeenCalledWith(expect.objectContaining({
            currentUserIsSuperAdmin: isSuperAdmin,
            canManageUser: mutationsPermissions,
        }))
        expect(mocks.dialogs).toHaveBeenCalledWith(expect.objectContaining({
            currentUserIsSuperAdmin: isSuperAdmin,
        }))
    })

    it('кнопки UI вызывают контроллерные действия без дополнительных запросов из страницы', () => {
        render(<AdminUsersPage currentUser={CURRENT_USER} />)
        fireEvent.click(screen.getByRole('button', { name: 'Обновить список' }))
        fireEvent.click(screen.getByRole('button', { name: 'Создать пользователя' }))
        expect(reload).toHaveBeenCalledOnce()
        expect(createOpen).toHaveBeenCalledOnce()
    })
})
