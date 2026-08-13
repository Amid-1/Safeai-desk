import {
    lazy,
    Suspense,
    useState,
    type ReactNode,
} from 'react'
import {
    NavLink,
    Navigate,
    Route,
    Routes,
    useNavigate,
} from 'react-router-dom'
import type { AuthUser } from './api/authApi'
import type { UserRole } from './api/types'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { ErrorState, LoadingState } from './components/StateBlock'

const LoginPage = lazy(() => import('./pages/LoginPage'))
const ChatPage = lazy(() => import('./pages/ChatPage'))
const AdminUsersPage = lazy(
    () => import('./pages/AdminUsersPage'),
)
const AdminOrganizationsPage = lazy(
    () => import('./pages/AdminOrganizationsPage'),
)
const KnowledgePage = lazy(
    () => import('./pages/KnowledgePage'),
)
const AdminAuditPage = lazy(
    () => import('./pages/AdminAuditPage'),
)
const AdminUsagePage = lazy(
    () => import('./pages/AdminUsagePage'),
)

type ProtectedRouteProps = {
    roles?: readonly UserRole[]
    children: ReactNode | ((currentUser: AuthUser) => ReactNode)
}

function hasAnyRole(
    user: AuthUser | null,
    roles: readonly UserRole[],
): boolean {
    return user?.roles.some((role) => roles.includes(role)) ?? false
}

function hasRequiredRole(
    user: AuthUser,
    roles?: readonly UserRole[],
): boolean {
    if (!roles || roles.length === 0) {
        return true
    }

    return user.roles.some((role) => roles.includes(role))
}

function isSuperAdmin(user: AuthUser | null): boolean {
    return user?.roles.includes('SUPER_ADMIN') ?? false
}

function getDefaultAuthenticatedRoute(user: AuthUser): string {
    return isSuperAdmin(user)
        ? '/admin/users'
        : '/chat'
}

function getDisplayRole(user: AuthUser | null): UserRole | null {
    if (!user) {
        return null
    }

    if (user.roles.includes('SUPER_ADMIN')) {
        return 'SUPER_ADMIN'
    }

    if (user.roles.includes('ADMIN')) {
        return 'ADMIN'
    }

    return 'USER'
}

function getNavLinkClass({
    isActive,
}: {
    isActive: boolean
}): string {
    return isActive
        ? 'nav-link active'
        : 'nav-link'
}

function AuthUnavailableState() {
    const {
        authError,
        reloadCurrentUser,
    } = useAuth()

    return (
        <div className="page narrow-page route-state">
            <ErrorState
                title="Сервис временно недоступен"
                message={
                    authError
                    ?? 'Не удалось проверить состояние сессии.'
                }
                action={
                    <button
                        type="button"
                        onClick={() => void reloadCurrentUser()}
                    >
                        Повторить
                    </button>
                }
            />
        </div>
    )
}

function AccessDeniedState() {
    return (
        <div className="page narrow-page route-state">
            <ErrorState
                title="Недостаточно прав"
                message="У вас нет доступа к этой странице."
            />
        </div>
    )
}

function RouteLoadingState() {
    return (
        <div className="page narrow-page route-state">
            <LoadingState message="Загрузка страницы..." />
        </div>
    )
}

function ProtectedRoute({
    roles,
    children,
}: ProtectedRouteProps) {
    const {
        currentUser,
        authStatus,
    } = useAuth()

    if (authStatus === 'loading') {
        return <LoadingState message="Проверка доступа..." />
    }

    if (authStatus === 'temporarily-unavailable') {
        return <AuthUnavailableState />
    }

    if (!currentUser) {
        return <Navigate to="/login" replace />
    }

    if (!hasRequiredRole(currentUser, roles)) {
        return <AccessDeniedState />
    }

    if (typeof children === 'function') {
        return <>{children(currentUser)}</>
    }

    return <>{children}</>
}

function RootRedirect() {
    const {
        currentUser,
        authStatus,
    } = useAuth()

    if (authStatus === 'loading') {
        return <LoadingState message="Проверка доступа..." />
    }

    if (authStatus === 'temporarily-unavailable') {
        return <AuthUnavailableState />
    }

    return (
        <Navigate
            to={
                currentUser
                    ? getDefaultAuthenticatedRoute(currentUser)
                    : '/login'
            }
            replace
        />
    )
}

function LoginRoute() {
    const {
        currentUser,
        authStatus,
    } = useAuth()

    if (authStatus === 'loading') {
        return <LoadingState message="Проверка доступа..." />
    }

    if (authStatus === 'temporarily-unavailable') {
        return <AuthUnavailableState />
    }

    if (currentUser) {
        return (
            <Navigate
                to={getDefaultAuthenticatedRoute(currentUser)}
                replace
            />
        )
    }

    return <LoginPage />
}

function NotFoundRoute() {
    const navigate = useNavigate()

    return (
        <div className="page narrow-page route-state">
            <ErrorState
                title="Страница не найдена"
                message="Проверьте адрес или вернитесь на главную страницу."
                action={
                    <button
                        type="button"
                        onClick={() => navigate('/', { replace: true })}
                    >
                        На главную
                    </button>
                }
            />
        </div>
    )
}

function AppLayout() {
    const navigate = useNavigate()
    const {
        currentUser,
        logoutUser,
    } = useAuth()
    const [logoutPending, setLogoutPending] = useState(false)

    const isAuthenticated = currentUser !== null
    const canAccessChat = hasAnyRole(
        currentUser,
        ['ADMIN', 'USER'],
    )
    const canAccessKnowledge = canAccessChat
    const canAccessAdmin = hasAnyRole(
        currentUser,
        [
            'ADMIN',
            'SUPER_ADMIN',
        ],
    )
    const canAccessOrganizations = isSuperAdmin(currentUser)
    const displayRole = getDisplayRole(currentUser)

    async function handleLogout() {
        if (logoutPending) {
            return
        }

        setLogoutPending(true)

        try {
            await logoutUser()
        } catch {
            // AuthContext очищает локальную сессию в finally.
        } finally {
            setLogoutPending(false)
            navigate('/login', { replace: true })
        }
    }

    return (
        <div className="app">
            {isAuthenticated && (
                <header className="topbar">
                    <div className="logo">SafeAI Desk</div>

                    <nav aria-label="Основная навигация">
                        {canAccessChat && (
                            <NavLink
                                to="/chat"
                                className={getNavLinkClass}
                            >
                                Чат
                            </NavLink>
                        )}

                        {canAccessKnowledge && (
                            <NavLink
                                to="/knowledge"
                                className={getNavLinkClass}
                            >
                                Базы знаний
                            </NavLink>
                        )}

                        {canAccessAdmin && (
                            <>
                                <NavLink
                                    to="/admin/users"
                                    className={getNavLinkClass}
                                >
                                    Пользователи
                                </NavLink>

                                {canAccessOrganizations && (
                                    <NavLink
                                        to="/admin/organizations"
                                        className={getNavLinkClass}
                                    >
                                        Организации
                                    </NavLink>
                                )}

                                <NavLink
                                    to="/admin/audit"
                                    className={getNavLinkClass}
                                >
                                    Аудит
                                </NavLink>

                                <NavLink
                                    to="/admin/usage"
                                    className={getNavLinkClass}
                                >
                                    Использование
                                </NavLink>
                            </>
                        )}
                    </nav>

                    <div className="topbar-user">
                        {currentUser && displayRole && (
                            <div className="current-user">
                                <span>{currentUser.email}</span>

                                <span
                                    className={
                                        displayRole === 'SUPER_ADMIN'
                                            ? 'role-badge role-super-admin'
                                            : displayRole === 'ADMIN'
                                                ? 'role-badge role-admin'
                                                : 'role-badge role-user'
                                    }
                                >
                                    {displayRole}
                                </span>
                            </div>
                        )}

                        <button
                            type="button"
                            disabled={logoutPending}
                            onClick={() => void handleLogout()}
                        >
                            {logoutPending
                                ? 'Выход...'
                                : 'Выйти'}
                        </button>
                    </div>
                </header>
            )}

            <main className="content">
                <Suspense fallback={<RouteLoadingState />}>
                    <Routes>
                        <Route
                            path="/"
                            element={<RootRedirect />}
                        />

                        <Route
                            path="/login"
                            element={<LoginRoute />}
                        />

                        <Route
                            path="/chat"
                            element={
                                <ProtectedRoute
                                    roles={['ADMIN', 'USER']}
                                >
                                    <ChatPage />
                                </ProtectedRoute>
                            }
                        />

                        <Route
                            path="/knowledge"
                            element={
                                <ProtectedRoute
                                    roles={['ADMIN', 'USER']}
                                >
                                    <KnowledgePage />
                                </ProtectedRoute>
                            }
                        />

                        <Route
                            path="/admin/users"
                            element={
                                <ProtectedRoute
                                    roles={[
                                        'ADMIN',
                                        'SUPER_ADMIN',
                                    ]}
                                >
                                    {(user) => (
                                        <AdminUsersPage
                                            currentUser={user}
                                        />
                                    )}
                                </ProtectedRoute>
                            }
                        />

                        <Route
                            path="/admin/organizations"
                            element={
                                <ProtectedRoute
                                    roles={['SUPER_ADMIN']}
                                >
                                    <AdminOrganizationsPage />
                                </ProtectedRoute>
                            }
                        />

                        <Route
                            path="/admin/audit"
                            element={
                                <ProtectedRoute
                                    roles={[
                                        'ADMIN',
                                        'SUPER_ADMIN',
                                    ]}
                                >
                                    <AdminAuditPage />
                                </ProtectedRoute>
                            }
                        />

                        <Route
                            path="/admin/usage"
                            element={
                                <ProtectedRoute
                                    roles={[
                                        'ADMIN',
                                        'SUPER_ADMIN',
                                    ]}
                                >
                                    <AdminUsagePage />
                                </ProtectedRoute>
                            }
                        />

                        <Route
                            path="*"
                            element={<NotFoundRoute />}
                        />
                    </Routes>
                </Suspense>
            </main>
        </div>
    )
}

function App() {
    return (
        <AuthProvider>
            <AppLayout />
        </AuthProvider>
    )
}

export default App
