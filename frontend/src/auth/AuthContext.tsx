// frontend/src/auth/AuthContext.tsx
import { createContext, useContext, useEffect, useState } from 'react'
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
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
    const [currentUser, setCurrentUser] = useState<AuthUser | null>(null)
    const [authLoading, setAuthLoading] = useState(true)

    async function reloadCurrentUser() {
        setAuthLoading(true)

        try {
            const user = await getCurrentUser()
            setCurrentUser(user)
        } catch {
            setCurrentUser(null)
        } finally {
            setAuthLoading(false)
        }
    }

    async function loginUser(request: LoginRequest) {
        await loginRequest(request)
        await reloadCurrentUser()
    }

    async function logoutUser() {
        try {
            await logoutRequest()
        } finally {
            setCurrentUser(null)
        }
    }

    useEffect(() => {
        void reloadCurrentUser()
    }, [])

    useEffect(() => {
        return subscribeUnauthorized(() => {
            setCurrentUser(null)
            setAuthLoading(false)
        })
    }, [])

    const authValue: AuthContextValue = {
        currentUser,
        authLoading,
        loginUser,
        logoutUser,
    }

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