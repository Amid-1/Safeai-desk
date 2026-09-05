// ============================================================
// frontend/src/App.test.tsx
// ============================================================
import {
    render,
    screen,
} from '@testing-library/react'
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
} from './api/authApi'

import type {
    AuthStatus,
} from './auth/AuthContext'

import App from './App'

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

vi.mock('./auth/useAuth', () => ({
    useAuth: () => authMock.state,
}))

vi.mock('./pages/LoginPage', () => ({
    default: () => (
        <div data-testid="login-page">
            Login page
        </div>
    ),
}))

vi.mock('./pages/ChatPage', () => ({
    default: () => (
        <div data-testid="chat-page">
            Chat page
        </div>
    ),
}))

vi.mock('./pages/KnowledgePage', () => ({
    default: () => (
        <div data-testid="knowledge-page">
            Knowledge page
        </div>
    ),
}))

vi.mock('./pages/KnowledgeDetailsPage', () => ({
    default: () => (
        <div data-testid="knowledge-details-page">
            Knowledge details page
        </div>
    ),
}))

vi.mock('./pages/AdminUsersPage', () => ({
    default: () => (
        <div data-testid="admin-users-page">
            Admin users page
        </div>
    ),
}))

vi.mock(
    './pages/AdminOrganizationsPage',
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

vi.mock('./pages/AdminAuditPage', () => ({
    default: () => (
        <div data-testid="admin-audit-page">
            Admin audit page
        </div>
    ),
}))

vi.mock('./pages/AdminUsagePage', () => ({
    default: () => (
        <div data-testid="admin-usage-page">
            Admin usage page
        </div>
    ),
}))

vi.mock('./pages/AdminModelsPage', () => ({
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
            <App />
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
            [
                '/admin/users',
                'admin-users-page',
            ],
            [
                '/admin/organizations',
                'admin-organizations-page',
            ],
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
                        <App />
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
                        <App />
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
            },
        )
    },
)
