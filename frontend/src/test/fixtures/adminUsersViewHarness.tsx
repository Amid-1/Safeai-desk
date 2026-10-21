// ============================================================
// frontend/src/test/fixtures/adminUsersViewHarness.tsx
// Общие тестовые фикстуры и моки представления пользователей.
// Используется исключительно в тестах и не входит в production-импорты.
// ============================================================

import { render } from '@testing-library/react'
import { vi } from 'vitest'
import type { AuthUser } from '../../features/auth/api/authApi'
import type { User, UserStatistics } from '../../entities/user/api/userApi'
import { AdminUsersView } from '../../features/admin-users/ui/AdminUsersView'
import type { AdminUsersViewProps } from '../../features/admin-users/ui/adminUsersViewTypes'

export const TEST_CURRENT_USER: AuthUser = {
    id: '11111111-1111-4111-8111-111111111111',
    organizationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    email: 'admin@example.com',
    fullName: null,
    enabled: true,
    roles: ['ADMIN'],
}

export const TEST_DIRECTORY_USER: User = {
    ...TEST_CURRENT_USER,
    id: '22222222-2222-4222-8222-222222222222',
    email: 'employee@example.com',
    fullName: 'Алексей Иванов',
    roles: ['USER'],
    version: 5,
    createdAt: '2026-10-19T00:00:00Z',
    updatedAt: '2026-10-19T00:00:00Z',
    lastLoginAt: null,
}

export const TEST_USER_STATISTICS: UserStatistics = {
    total: 100,
    administrators: 1,
    users: 99,
    enabled: 98,
    disabled: 2,
}

function createCallbacks() {
    return {
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
        canManageUser: vi.fn((_user: User): boolean => false),
        canPermanentlyDelete: vi.fn((_user: User): boolean => false),
    }
}

/**
 * Общая точка подготовки props. Отдельный набор моков создаётся для
 * каждого теста, чтобы ни вызовы, ни настройки одного теста не
 * переходили в следующий.
 */
export function mountAdminUsersView(
    overrides: Partial<AdminUsersViewProps> = {},
) {
    const callbacks = createCallbacks()

    const props: AdminUsersViewProps = {
        users: [TEST_DIRECTORY_USER],
        statistics: TEST_USER_STATISTICS,
        organizations: [],
        loadError: '',
        mutationError: '',
        success: '',
        loading: false,
        creating: false,
        hasPendingMutation: false,
        page: 0,
        totalPages: 3,
        filter: 'ALL',
        status: 'ALL',
        searchDraft: '',
        appliedSearch: '',
        totalMatched: 1,
        detailsLoadingUserId: null,
        currentUser: TEST_CURRENT_USER,
        currentUserIsSuperAdmin: false,
        ...callbacks,
        ...overrides,
    }

    let currentProps = props
    const result = render(<AdminUsersView {...currentProps} />)

    return {
        ...result,
        callbacks,
        /** Повторный рендер меняет только указанные props. */
        rerenderWith(changes: Partial<AdminUsersViewProps>) {
            currentProps = { ...currentProps, ...changes }
            result.rerender(<AdminUsersView {...currentProps} />)
        },
    }
}
