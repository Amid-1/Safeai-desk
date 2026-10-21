// frontend/src/features/admin-users/ui/AdminUsersUi.test.tsx
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {describe, expect, it, vi} from 'vitest'
import {PasswordFields} from './AdminUsersUi'

const props = {
    password: 'ValidPassword123!',
    passwordConfirm: 'OtherPassword123!',
    onPasswordChange: vi.fn(),
    onPasswordConfirmChange: vi.fn(),
    disabled: false,
}

describe('PasswordFields security UI', () => {
    it('shows byte length, mismatch and independently reveals password fields', async () => {
        const user = userEvent.setup()
        render(<PasswordFields {...props} />)
        expect(screen.getByText(/Пароли не совпадают/)).toBeInTheDocument()
        expect(screen.getByText(/Длина пароля:/)).toBeInTheDocument()
        await user.click(screen.getByRole('button', {name: 'Показать пароль'}))
        expect(screen.getByRole('button', {name: 'Скрыть пароль'})).toBeInTheDocument()
        expect(screen.getByRole('button', {name: 'Показать подтверждение'})).toBeInTheDocument()
    })

    it('counts UTF-8 bytes rather than JavaScript string length', () => {
        render(<PasswordFields {...props} password={'Я'.repeat(36) + 'A'} passwordConfirm="" />)
        expect(screen.getByText(/73 из 72 байт/)).toBeInTheDocument()
        expect(screen.getByText(/превышен допустимый предел/)).toBeInTheDocument()
    })
})
