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
import {
    FixedUserRole,
    UserRoleSelector,
} from '../UserRoleSelector'
import {
    parseAssignableUserRole,
} from '../userRole'


describe('UserRoleSelector', () => {
    it('renders a mutually exclusive radio group', async () => {
        const user = userEvent.setup()
        const onChange = vi.fn()

        const { rerender } = render(
            <UserRoleSelector
                name="create-user-role"
                value="USER"
                onChange={onChange}
            />,
        )

        const userRadio =
            screen.getByRole(
                'radio',
                { name: /Пользователь/i },
            ) as HTMLInputElement

        const adminRadio =
            screen.getByRole(
                'radio',
                { name: /Администратор/i },
            ) as HTMLInputElement

        expect(userRadio.checked).toBe(true)
        expect(adminRadio.checked).toBe(false)
        expect(userRadio.name).toBe(
            adminRadio.name,
        )

        await user.click(adminRadio)

        expect(onChange).toHaveBeenCalledTimes(1)
        expect(onChange).toHaveBeenCalledWith(
            'ADMIN',
        )

        rerender(
            <UserRoleSelector
                name="create-user-role"
                value="ADMIN"
                onChange={onChange}
            />,
        )

        expect(userRadio.checked).toBe(false)
        expect(adminRadio.checked).toBe(true)
    })

    it('never exposes SUPER_ADMIN as an assignable option', () => {
        render(
            <UserRoleSelector
                name="role"
                value="USER"
                onChange={() => undefined}
            />,
        )

        expect(
            screen.queryByRole(
                'radio',
                { name: /Суперадминистратор/i },
            ),
        ).toBeNull()
    })

    it('renders the ADMIN fixed role without an interactive control', () => {
        render(
            <FixedUserRole userRole="USER" />,
        )

        expect(
            screen.getByText('Пользователь'),
        ).toBeTruthy()
        expect(
            screen.queryByRole('radio'),
        ).toBeNull()
        expect(
            screen.queryByRole('checkbox'),
        ).toBeNull()
    })
})

describe('parseAssignableUserRole', () => {
    it('accepts only USER and ADMIN', () => {
        expect(
            parseAssignableUserRole('USER'),
        ).toBe('USER')
        expect(
            parseAssignableUserRole('ADMIN'),
        ).toBe('ADMIN')
        expect(
            parseAssignableUserRole(
                'SUPER_ADMIN',
            ),
        ).toBeNull()
        expect(
            parseAssignableUserRole(''),
        ).toBeNull()
    })
})
