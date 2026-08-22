import { useContext } from 'react'
import {
    AuthContext,
} from './authContext.definition'
import type {
    AuthContextValue,
} from './authContext.definition'

export function useAuth(): AuthContextValue {
    const context = useContext(AuthContext)

    if (!context) {
        throw new Error(
            'useAuth должен использоваться внутри AuthProvider',
        )
    }

    return context
}
