import {
    render,
    screen,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import { ApiError } from '../api/http'
import type {
    AuthStatus,
} from '../auth/AuthContext'
import LoginPage from './LoginPage'

const authMock = vi.hoisted(() => ({
    currentUser: null,
    authStatus:
        'unauthenticated' as AuthStatus,
    authLoading: false,
    authError: null as string | null,
    loginUser: vi.fn(),
    logoutUser: vi.fn(),
    reloadCurrentUser: vi.fn(),
}))

vi.mock('../auth/AuthContext', () => ({
    useAuth: () => authMock,
}))

describe('LoginPage', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        authMock.authStatus =
            'unauthenticated'
        authMock.authError = null
    })

    it('отправляется с клавиатуры и не trim-ит password', async () => {
        const user = userEvent.setup()

        authMock.loginUser.mockResolvedValue(
            undefined,
        )

        render(<LoginPage />)

        await user.type(
            screen.getByLabelText('Email'),
            '  USER@SAFEAI.TEST  ',
        )
        await user.type(
            screen.getByLabelText('Пароль'),
            ' secret ',
        )
        await user.keyboard('{Enter}')

        expect(
            authMock.loginUser,
        ).toHaveBeenCalledWith({
            email: 'USER@SAFEAI.TEST',
            password: ' secret ',
        })
    })

    it('двойной submit отправляет один request', async () => {
        const user = userEvent.setup()
        let resolveLogin!: () => void

        authMock.loginUser.mockReturnValue(
            new Promise<void>((resolve) => {
                resolveLogin = resolve
            }),
        )

        render(<LoginPage />)

        await user.type(
            screen.getByLabelText('Email'),
            'user@safeai.test',
        )
        await user.type(
            screen.getByLabelText('Пароль'),
            'secret',
        )

        const submit = screen.getByRole(
            'button',
            {
                name: 'Войти',
            },
        )

        await Promise.all([
            user.click(submit),
            user.click(submit),
        ])

        expect(
            authMock.loginUser,
        ).toHaveBeenCalledTimes(1)

        resolveLogin()
    })

    it('401 показывает generic error и очищает пароль', async () => {
        const user = userEvent.setup()

        authMock.loginUser.mockRejectedValue(
            new ApiError(
                'Internal authentication details',
                {
                    status: 401,
                    error:
                        'AUTHENTICATION_FAILED',
                    message:
                        'Internal authentication details',
                },
                401,
            ),
        )

        render(<LoginPage />)

        await user.type(
            screen.getByLabelText('Email'),
            'user@safeai.test',
        )
        const passwordInput =
            screen.getByLabelText('Пароль')

        await user.type(
            passwordInput,
            'secret',
        )
        await user.click(
            screen.getByRole('button', {
                name: 'Войти',
            }),
        )

        const alert =
            await screen.findByRole('alert')

        expect(alert).toHaveTextContent(
            'Неверный email или пароль.',
        )
        expect(alert).not.toHaveTextContent(
            'Internal authentication details',
        )
        expect(passwordInput).toHaveValue('')
    })

    it('429 использует Retry-After и блокирует submit', async () => {
        const user = userEvent.setup()

        authMock.loginUser.mockRejectedValue(
            new ApiError(
                'Rate limited',
                {
                    status: 429,
                    error:
                        'RATE_LIMIT_EXCEEDED',
                    retryAfterSeconds: 90,
                },
                429,
            ),
        )

        render(<LoginPage />)

        await user.type(
            screen.getByLabelText('Email'),
            'user@safeai.test',
        )
        await user.type(
            screen.getByLabelText('Пароль'),
            'secret',
        )
        await user.click(
            screen.getByRole('button', {
                name: 'Войти',
            }),
        )

        expect(
            await screen.findByRole('alert'),
        ).toHaveTextContent(
            'Повторите через 1 мин.',
        )

        expect(
            screen.getByRole('button', {
                name: 'Войти',
            }),
        ).toBeDisabled()
    })

    it('503 не показывается как invalid credentials', async () => {
        const user = userEvent.setup()

        authMock.loginUser.mockRejectedValue(
            new ApiError(
                'Service unavailable',
                {
                    status: 503,
                    error:
                        'SERVICE_UNAVAILABLE',
                },
                503,
            ),
        )

        render(<LoginPage />)

        await user.type(
            screen.getByLabelText('Email'),
            'user@safeai.test',
        )
        await user.type(
            screen.getByLabelText('Пароль'),
            'secret',
        )
        await user.click(
            screen.getByRole('button', {
                name: 'Войти',
            }),
        )

        const alert =
            await screen.findByRole('alert')

        expect(alert).toHaveTextContent(
            'Не удалось выполнить вход.',
        )
        expect(alert).not.toHaveTextContent(
            'Неверный email или пароль.',
        )
    })

    it('ошибка имеет role=alert и aria-invalid', async () => {
        const user = userEvent.setup()

        render(<LoginPage />)

        const email =
            screen.getByLabelText('Email')

        await user.type(email, ' ')
        await user.click(
            screen.getByRole('button', {
                name: 'Войти',
            }),
        )

        expect(
            await screen.findByRole('alert'),
        ).toBeInTheDocument()
        expect(email).toHaveAttribute(
            'aria-invalid',
            'true',
        )
    })
})
