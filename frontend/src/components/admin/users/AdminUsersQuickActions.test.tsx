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
                onInfo={() => undefined}
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

    it('ADMIN role cell explains the restriction without allowing a mutation', async () => {
        const user = userEvent.setup()
        const onInfo = vi.fn()
        const onEdit = vi.fn()
        render(
            <UserRoleQuickAction
                role="USER"
                email="user@test.com"
                editable={false}
                disabled={false}
                onEdit={onEdit}
                onInfo={onInfo}
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

        await user.click(screen.getByRole('button', {
            name: 'Информация о роли пользователя user@test.com',
        }))
        expect(onInfo).toHaveBeenCalledTimes(1)
        expect(onEdit).not.toHaveBeenCalled()
    })


    it('a pending operation disables both role interactions', async () => {
        const onInfo = vi.fn()
        const onEdit = vi.fn()
        const user = userEvent.setup()
        const {rerender} = render(
            <UserRoleQuickAction
                role="USER"
                email="user@test.com"
                editable={false}
                disabled
                onEdit={onEdit}
                onInfo={onInfo}
            />,
        )
        const infoButton = screen.getByRole('button', {
            name: 'Информация о роли пользователя user@test.com',
        })
        expect(infoButton).toBeDisabled()
        await user.click(infoButton)
        expect(onInfo).not.toHaveBeenCalled()

        rerender(
            <UserRoleQuickAction
                role="USER"
                email="user@test.com"
                editable
                disabled
                onEdit={onEdit}
                onInfo={onInfo}
            />,
        )
        const editButton = screen.getByRole('button', {
            name: 'Изменить роль пользователя user@test.com',
        })
        expect(editButton).toBeDisabled()
        await user.click(editButton)
        expect(onEdit).not.toHaveBeenCalled()
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
