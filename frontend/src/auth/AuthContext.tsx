// frontend/src/auth/AuthContext.tsx
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import {
    getCurrentUser,
    login as loginRequest,
    logout as logoutRequest,
} from '../api/authApi'
import type { AuthUser, LoginRequest } from '../api/authApi'
import { subscribeUnauthorized } from '../api/http'

type AuthContextValue = {
    currentUser: AuthUser | null
    authLoading: boolean
    loginUser: (request: LoginRequest) => Promise<void>
    logoutUser: () => Promise<void>
    reloadCurrentUser: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
    const [currentUser, setCurrentUser] = useState<AuthUser | null>(null)
    const [authLoading, setAuthLoading] = useState(true)

    const reloadCurrentUser = useCallback(async () => {
        setAuthLoading(true)

        try {
            const user = await getCurrentUser()
            setCurrentUser(user)
        } catch {
            setCurrentUser(null)
        } finally {
            setAuthLoading(false)
        }
    }, [])

    const loginUser = useCallback(async (request: LoginRequest) => {
        setAuthLoading(true)

        try {
            const user = await loginRequest(request)
            setCurrentUser(user)
        } finally {
            setAuthLoading(false)
        }
    }, [])

    const logoutUser = useCallback(async () => {
        setAuthLoading(true)

        try {
            await logoutRequest()
        } finally {
            setCurrentUser(null)
            setAuthLoading(false)
        }
    }, [])

    useEffect(() => {
        void reloadCurrentUser()
    }, [reloadCurrentUser])

    useEffect(() => {
        return subscribeUnauthorized(() => {
            setCurrentUser(null)
            setAuthLoading(false)
        })
    }, [])

    const authValue = useMemo<AuthContextValue>(() => ({
        currentUser,
        authLoading,
        loginUser,
        logoutUser,
        reloadCurrentUser,
    }), [
        currentUser,
        authLoading,
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

export function useAuth() {
    const context = useContext(AuthContext)

    if (!context) {
        throw new Error('useAuth must be used inside AuthProvider')
    }

    return context
}