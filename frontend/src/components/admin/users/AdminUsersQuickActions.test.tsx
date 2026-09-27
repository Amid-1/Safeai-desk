// frontend/src/components/admin/users/AdminUsersQuickActions.test.tsx
import {
    render,
    screen,
} from '@testing-library/react'
import userEvent
    from '@testing-library/user-event'
import {
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    UserRoleQuickAction,
    UserStatusQuickAction,
} from './AdminUsersQuickActions'

describe('AdminUsersQuickActions', () => {
    it('SUPER_ADMIN role cell exposes an explicit edit action', async () => {
        const user =
            userEvent.setup()

        const onEdit =
            vi.fn()

        render(
            <UserRoleQuickAction
                role="USER"
                email="user@test.com"
                editable
                disabled={false}
                onEdit={onEdit}
            />,
        )

        await user.click(
            screen.getByRole(
                'button',
                {
                    name:
                        'Изменить роль пользователя user@test.com',
                },
            ),
        )

        expect(
            onEdit,
        ).toHaveBeenCalledTimes(1)
    })

    it('ADMIN role cell remains read-only when role mutation is forbidden', () => {
        render(
            <UserRoleQuickAction
                role="USER"
                email="user@test.com"
                editable={false}
                disabled={false}
                onEdit={() => undefined}
            />,
        )

        expect(
            screen.queryByRole(
                'button',
                {
                    name:
                        'Изменить роль пользователя user@test.com',
                },
            ),
        ).toBeNull()

        expect(
            screen.getByText(
                'Пользователь',
            ),
        ).toBeInTheDocument()
    })

    it('status cell exposes the allowed enable/disable mutation', async () => {
        const user =
            userEvent.setup()

        const onToggle =
            vi.fn()

        render(
            <UserStatusQuickAction
                enabled
                email="user@test.com"
                editable
                disabled={false}
                onToggle={onToggle}
            />,
        )

        await user.click(
            screen.getByRole(
                'button',
                {
                    name:
                        'Отключить пользователя user@test.com',
                },
            ),
        )

        expect(
            onToggle,
        ).toHaveBeenCalledTimes(1)
    })

    it('protected status remains a non-interactive badge', () => {
        render(
            <UserStatusQuickAction
                enabled
                email="admin@test.com"
                editable={false}
                disabled={false}
                onToggle={() => undefined}
            />,
        )

        expect(
            screen.queryByRole(
                'button',
                {
                    name:
                        'Отключить пользователя admin@test.com',
                },
            ),
        ).toBeNull()

        expect(
            screen.getByText(
                'Включён',
            ),
        ).toBeInTheDocument()
    })
})
