// ============================================================
// frontend/src/App.tsx
// ============================================================

import {
    NavLink,
    Navigate,
    Route,
    Routes,
    useNavigate,
} from 'react-router-dom'
import type { ReactNode } from 'react'
import LoginPage from './pages/LoginPage'
import ChatPage from './pages/ChatPage'
import AdminUsersPage from './pages/AdminUsersPage'
import AdminOrganizationsPage from './pages/AdminOrganizationsPage'
import AdminAuditPage from './pages/AdminAuditPage'
import AdminUsagePage from './pages/AdminUsagePage'
import type { AuthUser } from './api/authApi'
import type { UserRole } from './api/types'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { ErrorState, LoadingState } from './components/StateBlock'

type ProtectedRouteProps = {
    roles?: UserRole[]
    children: ReactNode | ((currentUser: AuthUser) => ReactNode)
}

function hasAnyRole(
    user: AuthUser | null,
    roles: UserRole[]
): boolean {
    return user?.roles.some((role) => roles.includes(role)) ?? false
}

function hasRequiredRole(
    user: AuthUser,
    roles?: UserRole[]
): boolean {
    if (!roles || roles.length === 0) {
        return true
    }

    return user.roles.some((role) => roles.includes(role))
}

function isSuperAdmin(user: AuthUser | null): boolean {
    return user?.roles.includes('SUPER_ADMIN') ?? false
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
    return isActive ? 'nav-link active' : 'nav-link'
}

function AuthUnavailableState() {
    const { authError, reloadCurrentUser } = useAuth()

    return (
        <div className="page narrow-page">
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
        return <Navigate to="/chat" replace />
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
            to={currentUser ? '/chat' : '/login'}
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
        return <Navigate to="/chat" replace />
    }

    return <LoginPage />
}

function AppLayout() {
    const navigate = useNavigate()
    const {
        currentUser,
        logoutUser,
    } = useAuth()

    const isAuthenticated = currentUser !== null
    const canAccessAdmin = hasAnyRole(
        currentUser,
        ['ADMIN', 'SUPER_ADMIN']
    )
    const canAccessOrganizations = isSuperAdmin(currentUser)
    const displayRole = getDisplayRole(currentUser)

    async function handleLogout() {
        await logoutUser()
        navigate('/login', { replace: true })
    }

    return (
        <div className="app">
            {isAuthenticated && (
                <header className="topbar">
                    <div className="logo">SafeAI Desk</div>

                    <nav aria-label="Основная навигация">
                        <NavLink
                            to="/chat"
                            className={getNavLinkClass}
                        >
                            Чат
                        </NavLink>

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
                            onClick={() => void handleLogout()}
                        >
                            Выйти
                        </button>
                    </div>
                </header>
            )}

            <main className="content">
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
                            <ProtectedRoute>
                                <ChatPage />
                            </ProtectedRoute>
                        }
                    />

                    <Route
                        path="/admin/users"
                        element={
                            <ProtectedRoute
                                roles={['ADMIN', 'SUPER_ADMIN']}
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
                                roles={['ADMIN', 'SUPER_ADMIN']}
                            >
                                <AdminAuditPage />
                            </ProtectedRoute>
                        }
                    />

                    <Route
                        path="/admin/usage"
                        element={
                            <ProtectedRoute
                                roles={['ADMIN', 'SUPER_ADMIN']}
                            >
                                <AdminUsagePage />
                            </ProtectedRoute>
                        }
                    />

                    <Route
                        path="*"
                        element={<Navigate to="/" replace />}
                    />
                </Routes>
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

