// ============================================================
// frontend/src/auth/AuthContext.tsx
// ============================================================

import {
    createContext,
    useCallback,
    useContext,
    useEffect,
    useMemo,
    useState,
} from 'react'
import type { ReactNode } from 'react'
import {
    getCurrentUser,
    login as loginRequest,
    logout as logoutRequest,
} from '../api/authApi'
import type { AuthUser, LoginRequest } from '../api/authApi'
import {
    ApiError,
    getApiErrorMessage,
    subscribeUnauthorized,
} from '../api/http'

export type AuthStatus =
    | 'loading'
    | 'authenticated'
    | 'unauthenticated'
    | 'temporarily-unavailable'

type AuthContextValue = {
    currentUser: AuthUser | null
    authStatus: AuthStatus
    authLoading: boolean
    authError: string | null
    loginUser: (request: LoginRequest) => Promise<void>
    logoutUser: () => Promise<void>
    reloadCurrentUser: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
    const [currentUser, setCurrentUser] = useState<AuthUser | null>(null)
    const [authStatus, setAuthStatus] = useState<AuthStatus>('loading')
    const [authError, setAuthError] = useState<string | null>(null)

    const reloadCurrentUser = useCallback(async () => {
        setAuthStatus('loading')
        setAuthError(null)

        try {
            const user = await getCurrentUser()

            setCurrentUser(user)
            setAuthStatus('authenticated')
        } catch (error) {
            if (error instanceof ApiError && error.status === 401) {
                setCurrentUser(null)
                setAuthStatus('unauthenticated')
                return
            }

            setAuthError(
                getApiErrorMessage(
                    error,
                    'Не удалось проверить состояние сессии'
                )
            )
            setAuthStatus('temporarily-unavailable')
        }
    }, [])

    const loginUser = useCallback(async (request: LoginRequest) => {
        setAuthError(null)

        const user = await loginRequest(request)

        setCurrentUser(user)
        setAuthStatus('authenticated')
    }, [])

    const logoutUser = useCallback(async () => {
        setAuthStatus('loading')
        setAuthError(null)

        try {
            await logoutRequest()
        } finally {
            setCurrentUser(null)
            setAuthStatus('unauthenticated')
        }
    }, [])

    useEffect(() => {
        void reloadCurrentUser()
    }, [reloadCurrentUser])

    useEffect(() => {
        return subscribeUnauthorized(() => {
            setCurrentUser(null)
            setAuthError(null)
            setAuthStatus('unauthenticated')
        })
    }, [])

    const authValue = useMemo<AuthContextValue>(() => ({
        currentUser,
        authStatus,
        authLoading: authStatus === 'loading',
        authError,
        loginUser,
        logoutUser,
        reloadCurrentUser,
    }), [
        currentUser,
        authStatus,
        authError,
        loginUser,
        logoutUser,
        reloadCurrentUser,
    ])

    return (
        <AuthContext.Provider value={authValue}>
            {children}
        </AuthContext.Provider>
    )
}

export function useAuth(): AuthContextValue {
    const context = useContext(AuthContext)

    if (!context) {
        throw new Error('useAuth должен использоваться внутри AuthProvider')
    }

    return context
}


