// ============================================================
// frontend/src/api/AuthContext.test.tsx
// ============================================================
import {
    StrictMode,
    type ReactNode,
} from 'react'
import {
    act,
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
import type { AuthUser } from '../api/authApi'
import { ApiError } from '../api/http'
import {
    AuthProvider,
    useAuth,
} from './AuthContext'

const apiMock = vi.hoisted(() => ({
    getCurrentUser: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
}))

const eventMock = vi.hoisted(() => ({
    unauthorizedHandler:
        null as (() => void) | null,
    authEventHandler:
        null as ((event: {
            type:
                | 'REFRESH_SUCCEEDED'
                | 'LOGOUT'
                | 'SESSION_REJECTED'
                | 'AUTH_USER_CHANGED'
        }) => void) | null,
    publishAuthEvent: vi.fn(),
}))

vi.mock('../api/authApi', () => ({
    getCurrentUser: apiMock.getCurrentUser,
    login: apiMock.login,
    logout: apiMock.logout,
}))

vi.mock('../api/authCoordinator', () => ({
    publishAuthEvent:
        eventMock.publishAuthEvent,
    subscribeAuthEvents: (
        handler: typeof eventMock.authEventHandler,
    ) => {
        eventMock.authEventHandler = handler

        return () => {
            eventMock.authEventHandler = null
        }
    },
}))

vi.mock('../api/http', async (importOriginal) => {
    const actual =
        await importOriginal<typeof import('../api/http')>()

    return {
        ...actual,
        subscribeUnauthorized: (
            handler: () => void,
        ) => {
            eventMock.unauthorizedHandler =
                handler

            return () => {
                eventMock.unauthorizedHandler =
                    null
            }
        },
    }
})

const USER: AuthUser = {
    id: '11111111-1111-1111-1111-111111111111',
    organizationId:
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    email: 'user@safeai.test',
    fullName: 'User',
    enabled: true,
    roles: ['USER'],
}

const ADMIN: AuthUser = {
    ...USER,
    id: '22222222-2222-2222-2222-222222222222',
    email: 'admin@safeai.test',
    roles: ['ADMIN'],
}

const SUPER_ADMIN: AuthUser = {
    ...USER,
    id: '33333333-3333-3333-3333-333333333333',
    email: 'super-admin@safeai.test',
    roles: ['SUPER_ADMIN'],
}

type Deferred<T> = {
    promise: Promise<T>
    resolve: (value: T) => void
    reject: (reason?: unknown) => void
}

function deferred<T>(): Deferred<T> {
    let resolve!: (value: T) => void
    let reject!: (reason?: unknown) => void

    const promise = new Promise<T>(
        (resolvePromise, rejectPromise) => {
            resolve = resolvePromise
            reject = rejectPromise
        },
    )

    return {
        promise,
        resolve,
        reject,
    }
}

function Probe() {
    const {
        currentUser,
        authStatus,
        authError,
        loginUser,
        logoutUser,
        reloadCurrentUser,
    } = useAuth()

    return (
        <div>
            <div data-testid="status">
                {authStatus}
            </div>
            <div data-testid="email">
                {currentUser?.email ?? 'none'}
            </div>
            <div data-testid="roles">
                {currentUser?.roles.join(',')
                    ?? 'none'}
            </div>
            <div data-testid="error">
                {authError ?? 'none'}
            </div>

            <button
                type="button"
                onClick={() => void loginUser({
                    email: 'admin@safeai.test',
                    password: 'secret',
                })}
            >
                Login
            </button>

            <button
                type="button"
                onClick={() => {
                    void logoutUser().catch(
                        () => undefined,
                    )
                }}
            >
                Logout
            </button>

            <button
                type="button"
                onClick={() =>
                    void reloadCurrentUser()
                }
            >
                Reload
            </button>
        </div>
    )
}

function renderProvider(
    children: ReactNode = <Probe />,
) {
    return render(
        <AuthProvider>
            {children}
        </AuthProvider>,
    )
}

describe('AuthContext concurrency and logout', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        eventMock.unauthorizedHandler = null
        eventMock.authEventHandler = null
    })

    it('старый /me не перезаписывает более новый login', async () => {
        const oldMe = deferred<AuthUser>()

        apiMock.getCurrentUser.mockReturnValue(
            oldMe.promise,
        )
        apiMock.login.mockResolvedValue(ADMIN)

        const user = userEvent.setup()

        renderProvider()

        await user.click(
            screen.getByRole('button', {
                name: 'Login',
            }),
        )

        expect(
            await screen.findByText(
                'admin@safeai.test',
            ),
        ).toBeInTheDocument()

        await act(async () => {
            oldMe.resolve(USER)
            await oldMe.promise
        })

        expect(screen.getByTestId('email'))
            .toHaveTextContent(
                'admin@safeai.test',
            )
    })

    it('старый /me не восстанавливает user после logout', async () => {
        const oldMe = deferred<AuthUser>()

        apiMock.getCurrentUser.mockReturnValue(
            oldMe.promise,
        )
        apiMock.logout.mockResolvedValue(undefined)

        const user = userEvent.setup()

        renderProvider()

        await user.click(
            screen.getByRole('button', {
                name: 'Logout',
            }),
        )

        expect(screen.getByTestId('status'))
            .toHaveTextContent('unauthenticated')

        await act(async () => {
            oldMe.resolve(USER)
            await oldMe.promise
        })

        expect(screen.getByTestId('email'))
            .toHaveTextContent('none')
    })

    it('старый 503 не переводит успешный login в unavailable', async () => {
        const oldMe = deferred<AuthUser>()

        apiMock.getCurrentUser.mockReturnValue(
            oldMe.promise,
        )
        apiMock.login.mockResolvedValue(ADMIN)

        const user = userEvent.setup()

        renderProvider()

        await user.click(
            screen.getByRole('button', {
                name: 'Login',
            }),
        )

        await act(async () => {
            oldMe.reject(
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

            try {
                await oldMe.promise
            } catch {
                // Ожидаемая ошибка устаревшего запроса.
            }
        })

        expect(screen.getByTestId('status'))
            .toHaveTextContent('authenticated')
        expect(screen.getByTestId('email'))
            .toHaveTextContent(
                'admin@safeai.test',
            )
    })

    it('unauthorized event инвалидирует незавершённый /me', async () => {
        const oldMe = deferred<AuthUser>()

        apiMock.getCurrentUser.mockReturnValue(
            oldMe.promise,
        )

        renderProvider()

        act(() => {
            eventMock.unauthorizedHandler?.()
        })

        expect(screen.getByTestId('status'))
            .toHaveTextContent('unauthenticated')

        await act(async () => {
            oldMe.resolve(USER)
            await oldMe.promise
        })

        expect(screen.getByTestId('email'))
            .toHaveTextContent('none')
    })

    it('manual reload побеждает незавершённый initial reload', async () => {
        const initial = deferred<AuthUser>()

        apiMock.getCurrentUser
            .mockReturnValueOnce(initial.promise)
            .mockResolvedValueOnce(SUPER_ADMIN)

        const user = userEvent.setup()

        renderProvider()

        await user.click(
            screen.getByRole('button', {
                name: 'Reload',
            }),
        )

        expect(
            await screen.findByText(
                'super-admin@safeai.test',
            ),
        ).toBeInTheDocument()

        await act(async () => {
            initial.resolve(USER)
            await initial.promise
        })

        expect(screen.getByTestId('roles'))
            .toHaveTextContent('SUPER_ADMIN')
    })

    it('StrictMode не оставляет stale auth state', async () => {
        apiMock.getCurrentUser.mockResolvedValue(USER)

        render(
            <StrictMode>
                <AuthProvider>
                    <Probe />
                </AuthProvider>
            </StrictMode>,
        )

        expect(
            await screen.findByText(
                'user@safeai.test',
            ),
        ).toBeInTheDocument()
        expect(screen.getByTestId('status'))
            .toHaveTextContent('authenticated')
    })

    it('успешный logout очищает state и broadcast-ит событие', async () => {
        apiMock.getCurrentUser.mockResolvedValue(USER)
        apiMock.logout.mockResolvedValue(undefined)

        const user = userEvent.setup()

        renderProvider()

        await screen.findByText('user@safeai.test')

        await user.click(
            screen.getByRole('button', {
                name: 'Logout',
            }),
        )

        expect(screen.getByTestId('status'))
            .toHaveTextContent('unauthenticated')
        expect(screen.getByTestId('email'))
            .toHaveTextContent('none')
        expect(
            eventMock.publishAuthEvent,
        ).toHaveBeenCalledWith('LOGOUT')
    })

    it('network failure не маскируется как подтверждённый logout', async () => {
        apiMock.getCurrentUser.mockResolvedValue(USER)
        apiMock.logout.mockRejectedValue(
            new ApiError(
                'Network error',
                {
                    status: 0,
                    error: 'NETWORK_ERROR',
                },
                0,
            ),
        )

        const user = userEvent.setup()

        renderProvider()

        await screen.findByText('user@safeai.test')

        await user.click(
            screen.getByRole('button', {
                name: 'Logout',
            }),
        )

        expect(screen.getByTestId('status'))
            .toHaveTextContent(
                'logout-unconfirmed',
            )
        expect(screen.getByTestId('email'))
            .toHaveTextContent('none')
        expect(
            eventMock.publishAuthEvent,
        ).not.toHaveBeenCalledWith('LOGOUT')
    })

    it('LOGOUT из другой вкладки очищает текущую вкладку', async () => {
        apiMock.getCurrentUser.mockResolvedValue(USER)

        renderProvider()

        await screen.findByText('user@safeai.test')

        act(() => {
            eventMock.authEventHandler?.({
                type: 'LOGOUT',
            })
        })

        expect(screen.getByTestId('status'))
            .toHaveTextContent('unauthenticated')
        expect(screen.getByTestId('email'))
            .toHaveTextContent('none')
    })

    it('AUTH_USER_CHANGED из другой вкладки обновляет роли', async () => {
        apiMock.getCurrentUser
            .mockResolvedValueOnce(USER)
            .mockResolvedValueOnce(SUPER_ADMIN)

        renderProvider()

        await screen.findByText('user@safeai.test')

        act(() => {
            eventMock.authEventHandler?.({
                type: 'AUTH_USER_CHANGED',
            })
        })

        expect(
            await screen.findByText(
                'super-admin@safeai.test',
            ),
        ).toBeInTheDocument()
        expect(screen.getByTestId('roles'))
            .toHaveTextContent('SUPER_ADMIN')
    })
})
