import { fireEvent, render, screen, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../../../api/authApi'
import type { User, UserStatistics } from '../../../api/userApi'
import { AdminUsersView } from './AdminUsersView'

vi.mock('../../ResizableScrollRegion', () => ({
    default: ({ upper, children, footer }: { upper: ReactNode, children: ReactNode, footer?: ReactNode }) => (
        <div>{upper}{children}{footer}</div>
    ),
}))
vi.mock('../UserActionsMenu', () => ({ default: () => null }))
vi.mock('../UserIdentityCell', () => ({ default: () => <span>Пользователь</span> }))

const auth: AuthUser = {
    id: '11111111-1111-1111-1111-111111111111',
    organizationId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    email: 'admin@test.com', fullName: null, enabled: true, roles: ['ADMIN'],
}
const row: User = {
    ...auth,
    id: '22222222-2222-2222-2222-222222222222',
    email: 'ivan.petrov@example.com',
    fullName: 'Иван Петров',
    roles: ['USER'],
    version: 1,
    createdAt: '2026-08-21T15:07:59Z',
    updatedAt: '2026-08-21T15:07:59Z',
    lastLoginAt: null,
}
const statistics: UserStatistics = {
    total: 10, administrators: 1, users: 9, enabled: 10, disabled: 0,
}
const callbacks = {
    onSearchDraftChange: vi.fn(),
    onApplySearch: vi.fn(),
    onClearSearch: vi.fn(),
    onSelectSearchSuggestion: vi.fn(),
    onStatusChange: vi.fn(),
    onCreate: vi.fn(),
    onFilterChange: vi.fn(),
    onPageChange: vi.fn(),
    onReload: vi.fn(),
    onDetails: vi.fn(),
    onEdit: vi.fn(),
    onRoles: vi.fn(),
    onResetPassword: vi.fn(),
    onEnabledChange: vi.fn(),
    onDelete: vi.fn(),
    canManageUser: () => false,
    canPermanentlyDelete: () => false,
}
function view(users: User[], appliedSearch: string, searchDraft = appliedSearch) {
    return render(<AdminUsersView
        users={users}
        statistics={statistics}
        organizations={[]}
        loadError="" mutationError="" success=""
        loading={false} creating={false} hasPendingMutation={false}
        page={0} totalPages={users.length ? 1 : 0} filter="ALL" status="ALL"
        searchDraft={searchDraft} appliedSearch={appliedSearch}
        totalMatched={users.length} detailsLoadingUserId={null}
        currentUser={auth} currentUserIsSuperAdmin={false}
        {...callbacks}
    />)
}

describe('Users directory search UI', () => {
    beforeEach(() => vi.clearAllMocks())
    it('explains empty search without hiding overall user count', () => {
        view([], 'does-not-exist')
        expect(screen.getByText(/По вашему запросу пользователи не найдены/)).toBeTruthy()
        expect(screen.getByText(/Всего пользователей: /).textContent).toContain('10')
        fireEvent.click(screen.getByRole('button', { name: 'Сбросить поиск' }))
        expect(callbacks.onClearSearch).toHaveBeenCalled()
    })

    it('offers a registered user and selects exact email', () => {
        view([row], 'Иван')
        const search = screen.getByRole('searchbox')
        fireEvent.focus(search)
        expect(screen.getByText('Совпадения · выберите пользователя')).toBeTruthy()
        fireEvent.click(screen.getByRole('button', { name: /Иван Петров/ }))
        expect(callbacks.onSelectSearchSuggestion).toHaveBeenCalledWith(row)
    })

    it('keeps role read-only for ADMIN, opens the real security explanation and closes it', () => {
        view([row], '')
        expect(screen.queryByRole('button', {
            name: `Изменить роль пользователя ${row.email}`,
        })).toBeNull()

        fireEvent.click(screen.getByRole('button', {
            name: `Информация о роли пользователя ${row.email}`,
        }))

        const dialog = screen.getByRole('dialog', {
            name: 'Системная роль пользователя',
        })
        expect(dialog.textContent).toContain(row.email)
        expect(dialog.textContent).toContain('Текущая роль')
        expect(dialog.textContent).toContain('только SUPER_ADMIN')
        expect(dialog.textContent).toContain('Просмотр роли не изменяет')
        expect(callbacks.onRoles).not.toHaveBeenCalled()

        fireEvent.click(within(dialog).getByRole('button', { name: /^Закрыть$/ }))
        expect(screen.queryByRole('dialog', {
            name: 'Системная роль пользователя',
        })).toBeNull()
    })

    it('renders protected enabled status without allowing unauthorized mutation', () => {
        view([row], '')
        expect(screen.getByText('Включён')).toBeTruthy()
        expect(screen.queryByRole('button', {
            name: `Отключить пользователя ${row.email}`,
        })).toBeNull()
        expect(callbacks.onEnabledChange).not.toHaveBeenCalled()
    })

    it('provides exactly the defined enable/disable status choices', () => {
        view([], '')
        const options = Array.from(screen.getByLabelText('Статус').querySelectorAll('option'))
        expect(options.map(option => option.textContent)).toEqual([
            'Все статусы', 'Включённые', 'Отключённые',
        ])
    })
})
