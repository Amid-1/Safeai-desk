// frontend/src/features/admin-users/ui/AdminUsersView.search.test.tsx
// ============================================================
// frontend/src/features/admin-users/ui/AdminUsersView.search.test.tsx
// Регрессионные проверки поиска, статусов и чтения системных ролей.
// ============================================================

import { fireEvent, screen, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../../auth/api/authApi'
import type { User, UserStatistics } from '../../../entities/user/api/userApi'
import {
    mountAdminUsersView,
    TEST_CURRENT_USER,
    TEST_DIRECTORY_USER,
} from '../../../test/fixtures/adminUsersViewHarness'

vi.mock('../../../shared/ui/ResizableScrollRegion', () => ({
    default: ({ upper, children, footer }: {
        upper: ReactNode
        children: ReactNode
        footer?: ReactNode
    }) => <div>{upper}{children}{footer}</div>,
}))
vi.mock('./UserActionsMenu', () => ({ default: () => null }))
vi.mock('./UserIdentityCell', () => ({ default: () => <span>Пользователь</span> }))

// Эти значения намеренно отличаются от production-набора:
// регрессии проверяются на двух независимых комбинациях пользователей и статистики.
const auth: AuthUser = {
    ...TEST_CURRENT_USER,
    organizationId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    email: 'admin@test.com',
}

const row: User = {
    ...TEST_DIRECTORY_USER,
    id: '22222222-2222-2222-2222-222222222222',
    organizationId: auth.organizationId,
    email: 'ivan.petrov@example.com',
    fullName: 'Иван Петров',
    version: 1,
    createdAt: '2026-08-21T15:07:59Z',
    updatedAt: '2026-08-21T15:07:59Z',
}

const statistics: UserStatistics = {
    total: 10,
    administrators: 1,
    users: 9,
    enabled: 10,
    disabled: 0,
}

function view(users: User[], appliedSearch: string, searchDraft = appliedSearch) {
    return mountAdminUsersView({
        currentUser: auth,
        users,
        statistics,
        page: 0,
        totalPages: users.length ? 1 : 0,
        searchDraft,
        appliedSearch,
        totalMatched: users.length,
    })
}

describe('Users directory search UI', () => {
    it('explains empty search without hiding overall user count', () => {
        const { callbacks } = view([], 'does-not-exist')
        expect(screen.getByText(/По вашему запросу пользователи не найдены/))
            .toBeInTheDocument()
        expect(screen.getByText(/Всего пользователей: /))
            .toHaveTextContent('10')
        fireEvent.click(screen.getByRole('button', { name: 'Сбросить поиск' }))
        expect(callbacks.onClearSearch).toHaveBeenCalledOnce()
    })

    it('offers a registered user and selects exact email', () => {
        const { callbacks } = view([row], 'Иван')
        const search = screen.getByRole('searchbox')
        fireEvent.focus(search)
        expect(screen.getByText('Совпадения · выберите пользователя'))
            .toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: /Иван Петров/ }))
        expect(callbacks.onSelectSearchSuggestion).toHaveBeenCalledOnce()
        expect(callbacks.onSelectSearchSuggestion).toHaveBeenCalledWith(row)
    })

    it('keeps role read-only for ADMIN, opens the real security explanation and closes it', () => {
        const { callbacks } = view([row], '')
        expect(screen.queryByRole('button', {
            name: `Изменить роль пользователя ${row.email}`,
        })).not.toBeInTheDocument()

        fireEvent.click(screen.getByRole('button', {
            name: `Информация о роли пользователя ${row.email}`,
        }))

        const dialog = screen.getByRole('dialog', {
            name: 'Системная роль пользователя',
        })
        expect(dialog).toHaveTextContent(row.email)
        expect(dialog).toHaveTextContent('Текущая роль')
        expect(dialog).toHaveTextContent('только SUPER_ADMIN')
        expect(dialog).toHaveTextContent('Просмотр роли не изменяет')
        expect(callbacks.onRoles).not.toHaveBeenCalled()

        fireEvent.click(within(dialog).getByRole('button', { name: /^Закрыть$/ }))
        expect(screen.queryByRole('dialog', {
            name: 'Системная роль пользователя',
        })).not.toBeInTheDocument()
    })

    it('renders protected enabled status without allowing unauthorized mutation', () => {
        const { callbacks } = view([row], '')
        expect(screen.getByText('Включён')).toBeInTheDocument()
        expect(screen.queryByRole('button', {
            name: `Отключить пользователя ${row.email}`,
        })).not.toBeInTheDocument()
        expect(callbacks.onEnabledChange).not.toHaveBeenCalled()
    })

    it('provides exactly the defined enable/disable status choices', () => {
        view([], '')
        const select = screen.getByLabelText('Статус') as HTMLSelectElement
        expect(Array.from(select.options, (option) => option.textContent)).toEqual([
            'Все статусы', 'Включённые', 'Отключённые',
        ])
    })

    it('не применяет поисковый фильтр автоматически при вводе или фокусе', () => {
        const { callbacks } = view([row], '')
        const search = screen.getByRole('searchbox')
        fireEvent.focus(search)
        fireEvent.change(search, { target: { value: 'Иван' } })
        expect(callbacks.onSearchDraftChange).toHaveBeenCalledWith('Иван')
        expect(callbacks.onApplySearch).not.toHaveBeenCalled()

        fireEvent.click(screen.getByRole('button', { name: 'Найти' }))
        expect(callbacks.onApplySearch).toHaveBeenCalledOnce()
    })

    it.each([
        { reason: 'идёт загрузка', overrides: { loading: true } },
        { reason: 'есть ошибка загрузки', overrides: { loadError: 'Ошибка' } },
        { reason: 'введён один символ', overrides: { searchDraft: 'И', appliedSearch: 'И' } },
        { reason: 'поиск ещё не применён', overrides: { appliedSearch: 'Другое' } },
    ])('не раскрывает подсказки, если $reason', ({ overrides }) => {
        const { rerenderWith } = view([row], 'Иван')
        rerenderWith(overrides)
        fireEvent.focus(screen.getByRole('searchbox'))
        expect(screen.queryByText('Совпадения · выберите пользователя'))
            .not.toBeInTheDocument()
    })

    it('закрывает подсказки клавишей Escape без отправки запроса', () => {
        const { callbacks } = view([row], 'Иван')
        const search = screen.getByRole('searchbox')
        fireEvent.focus(search)
        expect(screen.getByText('Совпадения · выберите пользователя'))
            .toBeInTheDocument()

        fireEvent.keyDown(search, { key: 'Escape' })
        expect(screen.queryByText('Совпадения · выберите пользователя'))
            .not.toBeInTheDocument()
        expect(callbacks.onApplySearch).not.toHaveBeenCalled()
    })
})
