// frontend/src/app/App.test.tsx
// ============================================================
// frontend/src/app/App.test.tsx
// ============================================================
import {
    render,
    screen,
    fireEvent,
} from '@testing-library/react'
import type {ReactNode} from 'react'
import {
    MemoryRouter,
} from 'react-router-dom'

import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'

import type {
    AuthUser,
} from '../features/auth/api/authApi'

import type {
    AuthStatus,
} from '../features/auth/state/AuthContext'

import App from './App'

const notificationHook = vi.hoisted(() => vi.fn<(scope: string | null, locationKey: string) => null>(() => null))

const authMock = vi.hoisted(() => ({
    state: {
        currentUser:
            null as AuthUser | null,

        authStatus:
            'unauthenticated' as AuthStatus,

        authLoading: false,

        authError:
            null as string | null,

        loginUser: vi.fn(),
        logoutUser: vi.fn(),
        reloadCurrentUser: vi.fn(),
    },
}))

// Route tests own the auth state; the real provider is tested separately.
vi.mock(import('../features/auth/state/AuthContext'), () => ({
    AuthProvider: ({children}: { children: ReactNode }) => (
        <>{children}</>
    ),
}))

vi.mock('../features/erasure/api/useErasureNotifications', () => ({
    useErasureNotifications: notificationHook,
}))

vi.mock('../features/auth/state/useAuth', () => ({
    useAuth: () => authMock.state,
}))

vi.mock('../pages/LoginPage', () => ({
    default: () => (
        <div data-testid="login-page">
            Login page
        </div>
    ),
}))

vi.mock('../pages/ChatPage', () => ({
    default: () => (
        <div data-testid="chat-page">
            Chat page
        </div>
    ),
}))

vi.mock('../pages/KnowledgePage', () => ({
    default: () => (
        <div data-testid="knowledge-page">
            Knowledge page
        </div>
    ),
}))

vi.mock('../pages/KnowledgeDetailsPage', () => ({
    default: () => (
        <div data-testid="knowledge-details-page">
            Knowledge details page
        </div>
    ),
}))

vi.mock('../pages/AdminUsersPage', () => ({
    default: () => (
        <div data-testid="admin-users-page">
            Admin users page
        </div>
    ),
}))

vi.mock(
    '../pages/AdminOrganizationsPage',
    () => ({
        default: () => (
            <div
                data-testid={
                    'admin-organizations-page'
                }
            >
                Admin organizations page
            </div>
        ),
    }),
)

vi.mock('../pages/AdminAuditPage', () => ({
    default: () => (
        <div data-testid="admin-audit-page">
            Admin audit page
        </div>
    ),
}))

vi.mock('../pages/AdminUsagePage', () => ({
    default: () => (
        <div data-testid="admin-usage-page">
            Admin usage page
        </div>
    ),
}))
vi.mock('../pages/AdminDashboardPage', () => ({
    default: () => <div data-testid="admin-dashboard-page">Dashboard</div>,
}))
vi.mock('../pages/AdminRetentionPage', () => ({
    default: () => <div data-testid="admin-retention-page">Retention</div>,
}))

vi.mock('../pages/AdminModelsPage', () => ({
    default: () => (
        <div data-testid="admin-models-page">
            Admin models page
        </div>
    ),
}))

const USER: AuthUser = {
    id:
        '11111111-1111-1111-1111-111111111111',

    organizationId:
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',

    email:
        'user@safeai.test',

    fullName:
        'User',

    enabled: true,

    roles: [
        'USER',
    ],
}

const ADMIN: AuthUser = {
    ...USER,

    id:
        '22222222-2222-2222-2222-222222222222',

    email:
        'admin@safeai.test',

    roles: [
        'ADMIN',
    ],
}

const SUPER_ADMIN: AuthUser = {
    ...USER,

    id:
        '33333333-3333-3333-3333-333333333333',

    email:
        'super-admin@safeai.test',

    roles: [
        'SUPER_ADMIN',
    ],
}

function setAuth(
    currentUser: AuthUser | null,
    authStatus: AuthStatus,
    authError: string | null = null,
): void {
    authMock.state.currentUser =
        currentUser

    authMock.state.authStatus =
        authStatus

    authMock.state.authLoading =
        authStatus === 'loading'

    authMock.state.authError =
        authError
}

function renderAt(
    path: string,
) {
    return render(
        <MemoryRouter
            initialEntries={[
                path,
            ]}
        >
            <App/>
        </MemoryRouter>,
    )
}

describe(
    'App routing and authorization',
    () => {
        beforeEach(() => {
            vi.clearAllMocks()

            setAuth(
                null,
                'unauthenticated',
            )
        })

        it('закрывает меню при выборе раздела и переносит фокус к содержимому', async () => {
            setAuth(ADMIN, 'authenticated')
            renderAt('/chat')
            await screen.findByTestId('chat-page')
            const menu = screen.getByRole('button', {name: 'Меню'})
            expect(menu).toHaveAttribute('aria-expanded', 'false')
            fireEvent.click(menu)
            expect(menu).toHaveAttribute('aria-expanded', 'true')
            fireEvent.click(screen.getByRole('link', {name: 'Базы знаний'}))
            await screen.findByTestId('knowledge-page')
            expect(menu).toHaveAttribute('aria-expanded', 'false')
            expect(screen.getByRole('main')).toHaveFocus()
        })

        it('Escape закрывает мобильное меню, а ссылка пропуска переводит фокус в main', async () => {
            setAuth(ADMIN, 'authenticated')
            renderAt('/chat')
            await screen.findByTestId('chat-page')
            const menu = screen.getByRole('button', {name: 'Меню'})
            vi.spyOn(menu, 'getClientRects').mockReturnValue({length: 1} as DOMRectList)
            fireEvent.click(menu)
            fireEvent.keyDown(window, {key: 'Escape'})
            expect(menu).toHaveAttribute('aria-expanded', 'false')
            expect(menu).toHaveFocus()
            fireEvent.click(screen.getByRole('link', {name: 'Перейти к содержимому'}))
            expect(screen.getByRole('main')).toHaveFocus()
        })

        it('не сохраняет страницу при смене организации текущего администратора', async () => {
            setAuth(ADMIN, 'authenticated')
            const view = renderAt('/admin/models')
            const first = await screen.findByTestId('admin-models-page')
            setAuth({...ADMIN, organizationId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'}, 'authenticated')
            view.rerender(<MemoryRouter initialEntries={['/admin/models']}><App/></MemoryRouter>)
            expect(await screen.findByTestId('admin-models-page')).not.toBe(first)
        })

        it('preserves the page when the authenticated actor scope has not changed', async () => {
            setAuth(ADMIN, 'authenticated')
            const view = renderAt('/admin/models')
            const first = await screen.findByTestId('admin-models-page')
            setAuth({...ADMIN, fullName: 'Updated name'}, 'authenticated')
            view.rerender(<MemoryRouter initialEntries={['/admin/models']}><App/></MemoryRouter>)
            expect(await screen.findByTestId('admin-models-page')).toBe(first)
        })

        it('remounts the page when the actor changes within the same organization', async () => {
            setAuth(ADMIN, 'authenticated')
            const view = renderAt('/admin/models')
            const first = await screen.findByTestId('admin-models-page')
            const next = {...ADMIN, id: USER.id}
            setAuth(next, 'authenticated')
            view.rerender(<MemoryRouter initialEntries={['/admin/models']}><App/></MemoryRouter>)
            expect(await screen.findByTestId('admin-models-page')).not.toBe(first)
            expect(first).not.toBeInTheDocument()
            expect(notificationHook).toHaveBeenLastCalledWith(
                JSON.stringify([next.id, next.organizationId, next.roles]), expect.any(String),
            )
        })

        it('remounts the page when roles change even if the route remains allowed', async () => {
            setAuth(ADMIN, 'authenticated')
            const view = renderAt('/admin/models')
            const first = await screen.findByTestId('admin-models-page')
            const next: AuthUser = {...ADMIN, roles: ['ADMIN', 'SUPER_ADMIN']}
            setAuth(next, 'authenticated')
            view.rerender(<MemoryRouter initialEntries={['/admin/models']}><App/></MemoryRouter>)
            expect(await screen.findByTestId('admin-models-page')).not.toBe(first)
            expect(first).not.toBeInTheDocument()
        })

        it('unmounts a live protected page when its account is disabled', async () => {
            setAuth(ADMIN, 'authenticated')
            const view = renderAt('/admin/models')
            const first = await screen.findByTestId('admin-models-page')
            setAuth(
                {...ADMIN, enabled: false} as unknown as AuthUser,
                'authenticated',
            )
            view.rerender(<MemoryRouter initialEntries={['/admin/models']}><App/></MemoryRouter>)
            expect(await screen.findByText('Недостаточно прав')).toBeInTheDocument()
            expect(first).not.toBeInTheDocument()
            expect(screen.queryByTestId('admin-models-page')).not.toBeInTheDocument()
            expect(notificationHook).toHaveBeenLastCalledWith(null, expect.any(String))
        })

        it.each(['loading', 'temporarily-unavailable'] as const)(
            'unmounts a protected page while authentication is %s', async status => {
                setAuth(ADMIN, 'authenticated')
                const view = renderAt('/admin/models')
                const first = await screen.findByTestId('admin-models-page')
                setAuth(null, status)
                view.rerender(<MemoryRouter initialEntries={['/admin/models']}><App/></MemoryRouter>)
                expect(first).not.toBeInTheDocument()
                expect(screen.queryByTestId('admin-models-page')).not.toBeInTheDocument()
                expect(notificationHook).toHaveBeenLastCalledWith(null, expect.any(String))
                setAuth(ADMIN, 'authenticated')
                view.rerender(<MemoryRouter initialEntries={['/admin/models']}><App/></MemoryRouter>)
                expect(await screen.findByTestId('admin-models-page')).not.toBe(first)
            },
        )

        it('не показывает закрытые страницы при отключённом пользователе', async () => {
            setAuth({...ADMIN, enabled: false} as unknown as AuthUser, 'authenticated')
            renderAt('/admin/models')
            expect(await screen.findByText('Недостаточно прав')).toBeInTheDocument()
            expect(screen.queryByTestId('admin-models-page')).not.toBeInTheDocument()
        })

        it(
            'перенаправляет неавторизованного с /chat на /login',
            async () => {
                renderAt('/chat')

                expect(
                    await screen
                        .findByTestId(
                            'login-page',
                        ),
                ).toBeInTheDocument()
            },
        )

        it(
            'перенаправляет авторизованного с /login на /chat',
            async () => {
                setAuth(
                    USER,
                    'authenticated',
                )

                renderAt('/login')

                expect(
                    await screen
                        .findByTestId(
                            'chat-page',
                        ),
                ).toBeInTheDocument()
            },
        )

        it.each([
            [SUPER_ADMIN, '/admin/dashboard', 'admin-dashboard-page'],
            [ADMIN, '/chat', 'chat-page'],
            [USER, '/chat', 'chat-page'],
        ] as const)('выбирает главную страницу по роли: %j', async (user, home, testId) => {
            for (const path of ['/', '/login']) {
                setAuth(user, 'authenticated')
                const view = renderAt(path)
                expect(await screen.findByTestId(testId)).toBeInTheDocument()
                expect(screen.getByTitle('На главную')).toHaveAttribute('href', home)
                view.unmount()
            }
        })

        it('возвращает суперадминистратора с каталога пользователей на обзор по логотипу', async () => {
            setAuth(SUPER_ADMIN, 'authenticated')
            renderAt('/admin/users')
            expect(await screen.findByTestId('admin-users-page')).toBeInTheDocument()
            fireEvent.click(screen.getByTitle('На главную'))
            expect(await screen.findByTestId('admin-dashboard-page')).toBeInTheDocument()
        })

        it(
            'включает chat-specific классы только для рабочего чата',
            async () => {
                setAuth(
                    USER,
                    'authenticated',
                )

                const view = renderAt('/chat')
                const chatPage = await screen.findByTestId('chat-page')

                expect(
                    chatPage.closest('.app'),
                ).toHaveClass('app--chat')
                expect(
                    chatPage.closest('main'),
                ).toHaveClass('content--chat')

                view.unmount()

                setAuth(
                    ADMIN,
                    'authenticated',
                )

                renderAt('/admin/audit')
                const auditPage = await screen.findByTestId('admin-audit-page')

                expect(
                    auditPage.closest('.app'),
                ).not.toHaveClass('app--chat')
                expect(
                    auditPage.closest('main'),
                ).not.toHaveClass('content--chat')
            },
        )

        it(
            'включает viewport workspace и для страницы конкретной базы знаний',
            async () => {
                setAuth(
                    USER,
                    'authenticated',
                )

                renderAt(
                    '/knowledge/28ae4cac-2f14-42af-85e9-e3f1385a249f',
                )

                const detailsPage =
                    await screen.findByTestId(
                        'knowledge-details-page',
                    )

                expect(
                    detailsPage.closest('.app'),
                ).toHaveClass(
                    'app--workspace',
                )

                expect(
                    detailsPage.closest('main'),
                ).toHaveClass(
                    'content--workspace',
                )
            },
        )

        it('для каталога моделей использует обычную прокрутку, не viewport workspace', async () => {
            setAuth(SUPER_ADMIN, 'authenticated')
            renderAt('/admin/models')
            const modelsPage = await screen.findByTestId('admin-models-page')
            expect(modelsPage.closest('.app')).not.toHaveClass('app--workspace')
            expect(modelsPage.closest('main')).not.toHaveClass('content--workspace')
        })

        it(
            'запрещает USER доступ к /admin/users',
            async () => {
                setAuth(
                    USER,
                    'authenticated',
                )

                renderAt(
                    '/admin/users',
                )

                expect(
                    await screen
                        .findByRole(
                            'alert',
                        ),
                ).toHaveTextContent(
                    'Недостаточно прав',
                )

                expect(
                    screen.queryByTestId(
                        'admin-users-page',
                    ),
                ).not.toBeInTheDocument()
            },
        )

        it(
            'запрещает ADMIN доступ к /admin/organizations',
            async () => {
                setAuth(
                    ADMIN,
                    'authenticated',
                )

                renderAt(
                    '/admin/organizations',
                )

                expect(
                    await screen
                        .findByRole(
                            'alert',
                        ),
                ).toHaveTextContent(
                    'Недостаточно прав',
                )

                expect(
                    screen.queryByTestId(
                        'admin-organizations-page',
                    ),
                ).not.toBeInTheDocument()
            },
        )

        it.each([
            ['/admin/dashboard', 'admin-dashboard-page'],
            [
                '/admin/users',
                'admin-users-page',
            ],
            [
                '/admin/organizations',
                'admin-organizations-page',
            ],
            ['/admin/retention', 'admin-retention-page'],
            [
                '/admin/audit',
                'admin-audit-page',
            ],
            [
                '/admin/usage',
                'admin-usage-page',
            ],
            [
                '/admin/models',
                'admin-models-page',
            ],
        ] as const)(
            'разрешает SUPER_ADMIN открыть %s',
            async (
                path,
                testId,
            ) => {
                setAuth(
                    SUPER_ADMIN,
                    'authenticated',
                )

                renderAt(path)

                expect(
                    await screen
                        .findByTestId(
                            testId,
                        ),
                ).toBeInTheDocument()
            },
        )

        it.each([ADMIN, USER])('запрещает доступ к Dashboard без SUPER_ADMIN: $email', async user => {
            setAuth(user, 'authenticated')
            renderAt('/admin/dashboard')
            expect(await screen.findByRole('alert')).toHaveTextContent('Недостаточно прав')
            expect(screen.queryByTestId('admin-dashboard-page')).not.toBeInTheDocument()
            expect(screen.queryByRole('link', {name: 'Обзор'})).not.toBeInTheDocument()
        })
        it.each([ADMIN, USER])('запрещает управление удалением без SUPER_ADMIN: $email', async user => {
            setAuth(user, 'authenticated')
            renderAt('/admin/retention')
            expect(await screen.findByRole('alert')).toHaveTextContent('Недостаточно прав')
            expect(screen.queryByTestId('admin-retention-page')).not.toBeInTheDocument()
        })

        it.each([
            [USER, '/admin/dashboard'],
            [USER, '/admin/erasure/requests/123'],
            [ADMIN, '/admin/organizations'],
            [ADMIN, '/admin/retention'],
            [ADMIN, '/admin/erasure/registry'],
            [SUPER_ADMIN, '/chat'],
            [SUPER_ADMIN, '/knowledge/123'],
            [SUPER_ADMIN, '/admin/erasure/intake'],
            [SUPER_ADMIN, '/my/erasure-requests/123'],
        ] as const)('does not start notification polling on forbidden route %#', async (user, path) => {
            setAuth(user, 'authenticated')
            renderAt(path)
            expect(await screen.findByRole('alert')).toHaveTextContent('Недостаточно прав')
            expect(notificationHook).toHaveBeenCalled()
            expect(notificationHook.mock.calls.every(([scope]) => scope === null)).toBe(true)
        })

        it.each([
            [USER, '/chat', 'chat-page'],
            [ADMIN, '/admin/users', 'admin-users-page'],
            [SUPER_ADMIN, '/admin/dashboard', 'admin-dashboard-page'],
        ] as const)('keeps notifications on allowed routes %#', async (user, path, testId) => {
            setAuth(user, 'authenticated')
            renderAt(path)
            await screen.findByTestId(testId)
            expect(notificationHook).toHaveBeenLastCalledWith(
                JSON.stringify([user.id, user.organizationId, [...user.roles].sort()]),
                expect.any(String),
            )
        })

        it('clears notification scope immediately when the current route becomes forbidden', async () => {
            setAuth(SUPER_ADMIN, 'authenticated')
            const view = renderAt('/admin/dashboard')
            await screen.findByTestId('admin-dashboard-page')
            expect(notificationHook).toHaveBeenLastCalledWith(expect.any(String), expect.any(String))
            setAuth(ADMIN, 'authenticated')
            view.rerender(<MemoryRouter initialEntries={['/admin/dashboard']}><App/></MemoryRouter>)
            expect(await screen.findByRole('alert')).toHaveTextContent('Недостаточно прав')
            expect(notificationHook).toHaveBeenLastCalledWith(null, expect.any(String))
        })

        it('does not poll on an authenticated unknown route', async () => {
            setAuth(USER, 'authenticated')
            renderAt('/unknown-route')
            expect(await screen.findByRole('alert')).toHaveTextContent('Страница не найдена')
            expect(notificationHook).toHaveBeenCalled()
            expect(notificationHook.mock.calls.every(([scope]) => scope === null)).toBe(true)
        })

        it(
            'показывает 404 state для неизвестного route',
            async () => {
                renderAt(
                    '/unknown-route',
                )

                expect(
                    await screen
                        .findByRole(
                            'alert',
                        ),
                ).toHaveTextContent(
                    'Страница не найдена',
                )
            },
        )

        it(
            'показывает loading state, пока проверяется /me',
            () => {
                setAuth(
                    null,
                    'loading',
                )

                renderAt('/chat')

                expect(
                    screen.getByRole(
                        'status',
                    ),
                ).toHaveTextContent(
                    'Проверка доступа...',
                )
            },
        )

        it(
            'показывает temporarily-unavailable при ошибке backend',
            () => {
                setAuth(
                    null,
                    'temporarily-unavailable',
                    'Backend временно недоступен',
                )

                renderAt('/chat')

                expect(
                    screen.getByRole(
                        'alert',
                    ),
                ).toHaveTextContent(
                    'Сервис временно недоступен',
                )

                expect(
                    screen.getByRole(
                        'alert',
                    ),
                ).toHaveTextContent(
                    'Backend временно недоступен',
                )
            },
        )

        it(
            'повторяет проверку авторизации по кнопке',
            async () => {
                setAuth(
                    null,
                    'temporarily-unavailable',
                    'Backend временно недоступен',
                )

                renderAt('/chat')

                screen
                    .getByRole(
                        'button',
                        {
                            name:
                                'Повторить',
                        },
                    )
                    .click()

                expect(
                    authMock.state
                        .reloadCurrentUser,
                ).toHaveBeenCalledTimes(
                    1,
                )
            },
        )

        it(
            'обновляет route access после изменения роли',
            async () => {
                setAuth(
                    ADMIN,
                    'authenticated',
                )

                const view =
                    renderAt(
                        '/admin/organizations',
                    )

                expect(
                    await screen
                        .findByRole(
                            'alert',
                        ),
                ).toHaveTextContent(
                    'Недостаточно прав',
                )

                setAuth(
                    SUPER_ADMIN,
                    'authenticated',
                )

                view.rerender(
                    <MemoryRouter
                        initialEntries={[
                            '/admin/organizations',
                        ]}
                    >
                        <App/>
                    </MemoryRouter>,
                )

                expect(
                    await screen
                        .findByTestId(
                            'admin-organizations-page',
                        ),
                ).toBeInTheDocument()
            },
        )

        it(
            'закрывает protected UI после disable/unauthorized',
            async () => {
                setAuth(
                    ADMIN,
                    'authenticated',
                )

                const view =
                    renderAt(
                        '/admin/audit',
                    )

                expect(
                    await screen
                        .findByTestId(
                            'admin-audit-page',
                        ),
                ).toBeInTheDocument()

                setAuth(
                    null,
                    'unauthenticated',
                )

                view.rerender(
                    <MemoryRouter
                        initialEntries={[
                            '/admin/audit',
                        ]}
                    >
                        <App/>
                    </MemoryRouter>,
                )

                expect(
                    await screen
                        .findByTestId(
                            'login-page',
                        ),
                ).toBeInTheDocument()

                expect(
                    screen.queryByTestId(
                        'admin-audit-page',
                    ),
                ).not.toBeInTheDocument()
                expect(notificationHook).toHaveBeenLastCalledWith(null, expect.any(String))
                expect(screen.queryByRole('navigation', {name: 'Основная навигация'})).not.toBeInTheDocument()
            },
        )

        it('skip link references the actual main content element', async () => {
            setAuth(ADMIN, 'authenticated')
            renderAt('/chat')

            await screen.findByTestId('chat-page')

            const link = screen.getByRole('link', {
                name: 'Перейти к содержимому',
            })

            const main = screen.getByRole('main')

            expect(main).toHaveAttribute('id', 'main-content')
            expect(main).toHaveAttribute('tabindex', '-1')
            expect(link).toHaveAttribute('href', '#main-content')
            expect(document.getElementById('main-content')).toBe(main)

            fireEvent.click(link)

            expect(main).toHaveFocus()
        })
    },
)
