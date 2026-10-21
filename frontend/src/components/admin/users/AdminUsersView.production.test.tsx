// ============================================================
// frontend/src/components/admin/users/AdminUsersView.production.test.tsx
// Регрессионные проверки представления, прав и управляющих действий.
// ============================================================

import { fireEvent, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import {
    mountAdminUsersView,
    TEST_CURRENT_USER,
    TEST_DIRECTORY_USER,
    TEST_USER_STATISTICS,
} from '../../../test/fixtures/adminUsersViewHarness'

// Заменяем только инфраструктуру окна и дочерние виджеты действий;
// реальный AdminUsersView, его секции и обработчики остаются в тесте.
vi.mock('../../ResizableScrollRegion', () => ({
    default: ({ upper, children, footer }: {
        upper: ReactNode
        children: ReactNode
        footer?: ReactNode
    }) => <div>{upper}{children}{footer}</div>,
}))
vi.mock('../UserActionsMenu', () => ({ default: () => null }))
vi.mock('../UserIdentityCell', () => ({ default: () => <span>Пользователь</span> }))

function mount(overrides: Parameters<typeof mountAdminUsersView>[0] = {}) {
    return mountAdminUsersView({
        currentUser: TEST_CURRENT_USER,
        users: [TEST_DIRECTORY_USER],
        statistics: TEST_USER_STATISTICS,
        totalPages: 3,
        ...overrides,
    })
}

describe('AdminUsersView — границы представления и прав', () => {
    it('не показывает организацию для ADMIN, но показывает SUPER_ADMIN', () => {
        const { rerenderWith } = mount()
        expect(screen.queryByRole('columnheader', { name: 'Организация' }))
            .not.toBeInTheDocument()

        rerenderWith({ currentUserIsSuperAdmin: true })
        expect(screen.getByRole('columnheader', { name: 'Организация' }))
            .toBeInTheDocument()
    })

    it('не раскрывает подсказки из неактуального поиска', () => {
        mount({ searchDraft: 'Алексей', appliedSearch: 'Другое' })
        fireEvent.focus(screen.getByRole('searchbox'))
        expect(screen.queryByText('Совпадения · выберите пользователя'))
            .not.toBeInTheDocument()
    })

    it('обновляет статус и роль по отдельным callback без сетевых запросов из View', () => {
        const { callbacks } = mount()
        fireEvent.change(screen.getByLabelText('Статус'), {
            target: { value: 'DISABLED' },
        })
        expect(callbacks.onStatusChange).toHaveBeenCalledWith('DISABLED')

        fireEvent.click(screen.getByRole('button', { name: /Администраторы/ }))
        expect(callbacks.onFilterChange).toHaveBeenCalledWith('ADMIN')
    })

    it('ограничивает действия во время мутации, включая создание и фильтры', () => {
        const { callbacks } = mount({ hasPendingMutation: true })
        const createButton = screen.getByRole('button', { name: /Создать пользователя/ })
        expect(createButton).toBeDisabled()
        expect(screen.getByRole('searchbox')).toBeDisabled()
        expect(screen.getByLabelText('Статус')).toBeDisabled()
        expect(screen.getByRole('button', { name: /Администраторы/ })).toBeDisabled()
        expect(screen.getByRole('button', { name: 'Найти' })).toBeDisabled()

        fireEvent.click(createButton)
        expect(callbacks.onCreate).not.toHaveBeenCalled()
        expect(callbacks.onFilterChange).not.toHaveBeenCalled()
    })

    it('показывает повтор загрузки вместо устаревших строк после ошибки', () => {
        const { callbacks } = mount({ loadError: 'Сервер недоступен' })
        expect(screen.queryByRole('table')).not.toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))
        expect(callbacks.onReload).toHaveBeenCalledOnce()
    })

    it('передаёт на следующую страницу, но не выполняет локальную подмену данных', () => {
        const { callbacks } = mount()
        fireEvent.click(screen.getByRole('button', { name: 'Вперёд' }))
        expect(callbacks.onPageChange).toHaveBeenCalledWith(1)
        expect(screen.getByText(/Всего пользователей: /)).toHaveTextContent('100')
    })

    it('передаёт открытие карточки конкретного пользователя без изменения роли', () => {
        const { callbacks } = mount()
        fireEvent.click(screen.getByRole('button', {
            name: `Подробнее о пользователе ${TEST_DIRECTORY_USER.email}`,
        }))
        expect(callbacks.onDetails).toHaveBeenCalledOnce()
        expect(callbacks.onDetails).toHaveBeenCalledWith(TEST_DIRECTORY_USER)
        expect(callbacks.onRoles).not.toHaveBeenCalled()
    })

    it('скрывает неактуальную таблицу во время загрузки', () => {
        mount({ loading: true })
        expect(screen.queryByRole('table')).not.toBeInTheDocument()
        expect(screen.getByRole('button', { name: 'Найти' })).toBeDisabled()
        expect(screen.getByText('Загрузка пользователей...')).toBeInTheDocument()
    })

    it('не подменяет полномочия на роли, даже если управление профилем разрешено', () => {
        const { callbacks } = mount({
            canManageUser: () => true,
            currentUserIsSuperAdmin: false,
        })
        expect(screen.queryByRole('button', {
            name: `Изменить роль пользователя ${TEST_DIRECTORY_USER.email}`,
        })).not.toBeInTheDocument()
        expect(callbacks.onRoles).not.toHaveBeenCalled()
    })
})
