import { createContext } from 'react'
import type {
    AuthUser,
    LoginRequest,
} from '../api/authApi'

export type AuthStatus =
    | 'loading'
    | 'authenticated'
    | 'unauthenticated'
    | 'temporarily-unavailable'
    | 'logout-unconfirmed'

export type AuthContextValue = {
    currentUser: AuthUser | null
    authStatus: AuthStatus
    authLoading: boolean
    authError: string | null
    loginUser: (
        request: LoginRequest,
    ) => Promise<void>
    logoutUser: () => Promise<void>
    reloadCurrentUser: () => Promise<void>
}

export const AuthContext =
    createContext<AuthContextValue | null>(null)
