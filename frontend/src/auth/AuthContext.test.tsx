// ============================================================
// frontend/src/auth/AuthContext.test.tsx
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

type AuthApiModule =
    typeof import('../api/authApi')

type AuthCoordinatorModule =
    typeof import('../api/authCoordinator')

type HttpModule =
    typeof import('../api/http')

type AuthEventHandler =
    Parameters<
        AuthCoordinatorModule[
            'subscribeAuthEvents'
        ]
    >[0]

type AuthChannelEvent =
    Parameters<AuthEventHandler>[0]

type UnauthorizedHandler =
    Parameters<
        HttpModule[
            'subscribeUnauthorized'
        ]
    >[0]

type UnauthorizedReason =
    Parameters<UnauthorizedHandler>[0]

const apiMock = vi.hoisted(() => ({
    getCurrentUser:
        vi.fn<
            AuthApiModule[
                'getCurrentUser'
            ]
        >(),

    login:
        vi.fn<
            AuthApiModule[
                'login'
            ]
        >(),

    logout:
        vi.fn<
            AuthApiModule[
                'logout'
            ]
        >(),
}))

const eventMock = vi.hoisted(() => ({
    unauthorizedHandler:
        null as UnauthorizedHandler | null,

    authEventHandler:
        null as AuthEventHandler | null,

    publishAuthEvent:
        vi.fn<
            AuthCoordinatorModule[
                'publishAuthEvent'
            ]
        >(),
}))

vi.mock('../api/authApi', () => {
    const moduleMock: Pick<
        AuthApiModule,
        | 'getCurrentUser'
        | 'login'
        | 'logout'
    > = {
        getCurrentUser:
            apiMock.getCurrentUser,
        login:
            apiMock.login,
        logout:
            apiMock.logout,
    }

    return moduleMock
})

vi.mock(
    '../api/authCoordinator',
    () => {
        const subscribeAuthEvents:
            AuthCoordinatorModule[
                'subscribeAuthEvents'
            ] = (handler) => {
                eventMock.authEventHandler =
                    handler

                return () => {
                    if (
                        eventMock
                            .authEventHandler
                        === handler
                    ) {
                        eventMock
                            .authEventHandler =
                            null
                    }
                }
            }

        const moduleMock: Pick<
            AuthCoordinatorModule,
            | 'publishAuthEvent'
            | 'subscribeAuthEvents'
        > = {
            publishAuthEvent:
                eventMock.publishAuthEvent,
            subscribeAuthEvents,
        }

        return moduleMock
    },
)

vi.mock(
    '../api/http',
    async (importOriginal) => {
        const actual =
            await importOriginal<
                HttpModule
            >()

        const subscribeUnauthorized:
            HttpModule[
                'subscribeUnauthorized'
            ] = (handler) => {
                eventMock
                    .unauthorizedHandler =
                    handler

                return () => {
                    if (
                        eventMock
                            .unauthorizedHandler
                        === handler
                    ) {
                        eventMock
                            .unauthorizedHandler =
                            null
                    }
                }
            }

        const moduleMock:
            HttpModule = {
            ...actual,
            subscribeUnauthorized,
        }

        return moduleMock
    },
)

function emitAuthEvent(
    type: AuthChannelEvent['type'],
): void {
    const handler =
        eventMock.authEventHandler

    if (!handler) {
        throw new Error(
            'Auth event handler не зарегистрирован',
        )
    }

    const event:
        AuthChannelEvent = {
        type,
        sourceTabId:
            'other-test-tab',
        occurredAt:
            Date.now(),
    }

    handler(event)
}

function emitUnauthorized(
    reason:
        UnauthorizedReason =
        'request-unauthorized',
): void {
    const handler =
        eventMock.unauthorizedHandler

    if (!handler) {
        throw new Error(
            'Unauthorized handler не зарегистрирован',
        )
    }

    handler(reason)
}

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
            emitUnauthorized()
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
            emitAuthEvent('LOGOUT')
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
            emitAuthEvent(
                'AUTH_USER_CHANGED',
            )
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