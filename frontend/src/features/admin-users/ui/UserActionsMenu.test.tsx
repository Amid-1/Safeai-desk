// frontend/src/features/admin-users/ui/UserActionsMenu.test.tsx
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
import UserActionsMenu
    from './UserActionsMenu'

function renderMenu(
    disabled = false,
    canChangeRole = true,
) {
    const actions = {
        onDetails: vi.fn(),
        onEdit: vi.fn(),
        onRoles: vi.fn(),
        onResetPassword: vi.fn(),
        onToggleEnabled: vi.fn(),
        onDelete: vi.fn(),
    }

    render(
        <UserActionsMenu
            userLabel="user@test.com"
            disabled={disabled}
            canManage
            canChangeRole={
                canChangeRole
            }
            canDelete
            enabled
            {...actions}
        />,
    )

    return actions
}

describe('UserActionsMenu', () => {
    it('keeps only the details button visible and moves editing into a portal', async () => {
        const user = userEvent.setup()
        const actions = renderMenu()
        expect(screen.queryByRole('button', {name: 'Изменить email и имя'})).toBeNull()
        const trigger = screen.getByRole('button', {
            name: 'Дополнительные действия для user@test.com',
        })
        await user.click(trigger)
        const edit = screen.getByRole('button', {name: 'Изменить email и имя'})
        expect(document.body.contains(edit)).toBe(true)
        await user.click(edit)
        expect(actions.onEdit).toHaveBeenCalledTimes(1)
        expect(edit).not.toBeInTheDocument()
    })

    it('closes on an outside click without triggering an operation', async () => {
        const user = userEvent.setup()
        const actions = renderMenu()
        await user.click(screen.getByRole('button', {
            name: 'Дополнительные действия для user@test.com',
        }))
        await user.click(document.body)
        expect(screen.queryByRole('button', {name: 'Установить новый пароль'})).toBeNull()
        expect(actions.onResetPassword).not.toHaveBeenCalled()
    })

    it('uses a normal popover and shows role mutation only when authorized', async () => {
        const user =
            userEvent.setup()

        renderMenu()

        await user.click(
            screen.getByRole(
                'button',
                {
                    name:
                        'Дополнительные действия для user@test.com',
                },
            ),
        )

        expect(
            screen.queryByRole(
                'menu',
            ),
        ).not.toBeInTheDocument()

        expect(
            screen.getByRole(
                'button',
                {
                    name:
                        'Изменить роль',
                },
            ),
        ).toBeInTheDocument()
    })

    it('does not offer role mutation to ordinary ADMIN', async () => {
        const user =
            userEvent.setup()

        renderMenu(
            false,
            false,
        )

        await user.click(
            screen.getByRole(
                'button',
                {
                    name:
                        'Дополнительные действия для user@test.com',
                },
            ),
        )

        expect(
            screen.queryByRole(
                'button',
                {
                    name:
                        'Изменить роль',
                },
            ),
        ).toBeNull()

        expect(
            screen.getByRole(
                'button',
                {
                    name:
                        'Установить новый пароль',
                },
            ),
        ).toBeInTheDocument()
    })

    it('Escape closes popup and restores focus to trigger', async () => {
        const user =
            userEvent.setup()

        renderMenu()

        const trigger =
            screen.getByRole(
                'button',
                {
                    name:
                        'Дополнительные действия для user@test.com',
                },
            )

        await user.click(
            trigger,
        )

        await user.keyboard(
            '{Escape}',
        )

        expect(
            screen.queryByRole(
                'button',
                {
                    name:
                        'Изменить роль',
                },
            ),
        ).not.toBeInTheDocument()

        expect(
            trigger,
        ).toHaveFocus()
    })

    it('disabled blocks actions', async () => {
        const user =
            userEvent.setup()

        const actions =
            renderMenu(true)

        await user.click(
            screen.getByRole(
                'button',
                {
                    name:
                        'Подробнее',
                },
            ),
        )

        expect(
            actions.onDetails,
        ).not.toHaveBeenCalled()

        expect(
            screen.getByRole(
                'button',
                {
                    name:
                        'Дополнительные действия для user@test.com',
                },
            ),
        ).toBeDisabled()
    })
})
