// ============================================================
// frontend/src/components/admin/UserActionsMenu.test.tsx
// ============================================================
import {
    render,
    screen,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import UserActionsMenu from './UserActionsMenu'

function renderMenu(
    disabled = false,
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
            disabled={disabled}
            canManage
            canDelete
            enabled
            {...actions}
        />,
    )

    return actions
}

describe('UserActionsMenu', () => {
    it('использует обычный popover, а не неполный ARIA menu pattern', async () => {
        const user = userEvent.setup()

        renderMenu()

        await user.click(
            screen.getByRole('button', {
                name:
                    'Дополнительные действия',
            }),
        )

        expect(
            screen.queryByRole('menu'),
        ).not.toBeInTheDocument()

        expect(
            screen.getByRole('button', {
                name:
                    'Управление ролями',
            }),
        ).toBeInTheDocument()
    })

    it('Escape закрывает popup и возвращает focus trigger', async () => {
        const user = userEvent.setup()

        renderMenu()

        const trigger = screen.getByRole(
            'button',
            {
                name:
                    'Дополнительные действия',
            },
        )

        await user.click(trigger)
        await user.keyboard('{Escape}')

        expect(
            screen.queryByRole('button', {
                name:
                    'Управление ролями',
            }),
        ).not.toBeInTheDocument()

        expect(trigger).toHaveFocus()
    })

    it('disabled блокирует все действия', async () => {
        const user = userEvent.setup()
        const actions = renderMenu(true)

        await user.click(
            screen.getByRole('button', {
                name: 'Подробнее',
            }),
        )

        expect(
            actions.onDetails,
        ).not.toHaveBeenCalled()

        expect(
            screen.getByRole('button', {
                name:
                    'Дополнительные действия',
            }),
        ).toBeDisabled()
    })
})
