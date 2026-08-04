// ============================================================
// frontend/src/api/AuthContext.tsx
// ============================================================
import {
    createContext,
    useCallback,
    useContext,
    useEffect,
    useMemo,
    useReducer,
    useRef,
} from 'react'
import type { ReactNode } from 'react'
import {
    getCurrentUser,
    login as loginRequest,
    logout as logoutRequest,
} from '../api/authApi'
import type {
    AuthUser,
    LoginRequest,
} from '../api/authApi'
import {
    ApiError,
    getApiErrorMessage,
    subscribeUnauthorized,
} from '../api/http'
import {
    publishAuthEvent,
    subscribeAuthEvents,
} from '../api/authCoordinator'

export type AuthStatus =
    | 'loading'
    | 'authenticated'
    | 'unauthenticated'
    | 'temporarily-unavailable'
    | 'logout-unconfirmed'

type AuthState =
    | {
        status: 'loading'
        user: null
        error: null
    }
    | {
        status: 'authenticated'
        user: AuthUser
        error: null
    }
    | {
        status: 'unauthenticated'
        user: null
        error: null
    }
    | {
        status: 'temporarily-unavailable'
        user: null
        error: string
    }
    | {
        status: 'logout-unconfirmed'
        user: null
        error: string
    }

type AuthAction =
    | { type: 'LOADING' }
    | {
        type: 'AUTHENTICATED'
        user: AuthUser
    }
    | { type: 'UNAUTHENTICATED' }
    | {
        type: 'TEMPORARILY_UNAVAILABLE'
        error: string
    }
    | {
        type: 'LOGOUT_UNCONFIRMED'
        error: string
    }

type AuthContextValue = {
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

type AuthOperation = {
    id: number
    signal: AbortSignal
}

const INITIAL_STATE: AuthState = {
    status: 'loading',
    user: null,
    error: null,
}

const AuthContext =
    createContext<AuthContextValue | null>(null)

export function AuthProvider({
    children,
}: {
    children: ReactNode
}) {
    const [state, dispatch] = useReducer(
        authReducer,
        INITIAL_STATE,
    )

    const operationSequenceRef = useRef(0)
    const operationControllerRef =
        useRef<AbortController | null>(null)

    const beginAuthOperation =
        useCallback((): AuthOperation => {
            operationControllerRef.current?.abort()

            operationSequenceRef.current += 1

            const controller =
                new AbortController()

            operationControllerRef.current =
                controller

            return {
                id: operationSequenceRef.current,
                signal: controller.signal,
            }
        }, [])

    const isCurrentOperation = useCallback(
        (operationId: number): boolean =>
            operationSequenceRef.current
                === operationId,
        [],
    )

    const invalidateOperations = useCallback(() => {
        operationControllerRef.current?.abort()
        operationSequenceRef.current += 1
    }, [])

    const applyUnauthenticated =
        useCallback(() => {
            invalidateOperations()
            dispatch({ type: 'UNAUTHENTICATED' })
        }, [invalidateOperations])

    const reloadCurrentUser =
        useCallback(async () => {
            const operation = beginAuthOperation()

            dispatch({ type: 'LOADING' })

            try {
                const user = await getCurrentUser({
                    signal: operation.signal,
                })

                if (
                    !isCurrentOperation(operation.id)
                ) {
                    return
                }

                dispatch({
                    type: 'AUTHENTICATED',
                    user,
                })
            } catch (error) {
                if (
                    !isCurrentOperation(operation.id)
                    || isAbortError(error)
                ) {
                    return
                }

                if (
                    error instanceof ApiError
                    && error.status === 401
                ) {
                    dispatch({
                        type: 'UNAUTHENTICATED',
                    })
                    return
                }

                dispatch({
                    type:
                        'TEMPORARILY_UNAVAILABLE',
                    error: getApiErrorMessage(
                        error,
                        'Не удалось проверить состояние сессии',
                    ),
                })
            }
        }, [
            beginAuthOperation,
            isCurrentOperation,
        ])

    const loginUser = useCallback(
        async (request: LoginRequest) => {
            const operation =
                beginAuthOperation()

            try {
                const user = await loginRequest(
                    request,
                    {
                        signal: operation.signal,
                    },
                )

                if (
                    !isCurrentOperation(operation.id)
                ) {
                    return
                }

                dispatch({
                    type: 'AUTHENTICATED',
                    user,
                })

                publishAuthEvent(
                    'AUTH_USER_CHANGED',
                )
            } catch (error) {
                if (
                    !isCurrentOperation(operation.id)
                    || isAbortError(error)
                ) {
                    return
                }

                if (
                    error instanceof ApiError
                    && (
                        error.status === 401
                        || error.status === 429
                    )
                ) {
                    dispatch({
                        type: 'UNAUTHENTICATED',
                    })
                } else {
                    dispatch({
                        type:
                            'TEMPORARILY_UNAVAILABLE',
                        error: getApiErrorMessage(
                            error,
                            'Не удалось выполнить вход',
                        ),
                    })
                }

                throw error
            }
        },
        [
            beginAuthOperation,
            isCurrentOperation,
        ],
    )

    const logoutUser = useCallback(async () => {
        const operation = beginAuthOperation()

        // Персональные данные скрываются немедленно.
        dispatch({ type: 'LOADING' })

        try {
            await logoutRequest({
                signal: operation.signal,
            })

            if (
                !isCurrentOperation(operation.id)
            ) {
                return
            }

            dispatch({
                type: 'UNAUTHENTICATED',
            })

            publishAuthEvent('LOGOUT')
        } catch (error) {
            if (
                !isCurrentOperation(operation.id)
                || isAbortError(error)
            ) {
                return
            }

            dispatch({
                type: 'LOGOUT_UNCONFIRMED',
                error: getApiErrorMessage(
                    error,
                    'Сервер не подтвердил завершение сессии',
                ),
            })

            throw error
        }
    }, [
        beginAuthOperation,
        isCurrentOperation,
    ])

    useEffect(() => {
        void reloadCurrentUser()

        return () => {
            operationControllerRef.current?.abort()
        }
    }, [reloadCurrentUser])

    useEffect(() => {
        return subscribeUnauthorized(() => {
            applyUnauthenticated()
        })
    }, [applyUnauthenticated])

    useEffect(() => {
        return subscribeAuthEvents((event) => {
            if (
                event.type === 'LOGOUT'
                || event.type === 'SESSION_REJECTED'
            ) {
                applyUnauthenticated()
                return
            }

            if (
                event.type === 'REFRESH_SUCCEEDED'
                || event.type === 'AUTH_USER_CHANGED'
            ) {
                void reloadCurrentUser()
            }
        })
    }, [
        applyUnauthenticated,
        reloadCurrentUser,
    ])

    const authValue =
        useMemo<AuthContextValue>(() => ({
            currentUser: state.user,
            authStatus: state.status,
            authLoading:
                state.status === 'loading',
            authError: state.error,
            loginUser,
            logoutUser,
            reloadCurrentUser,
        }), [
            state,
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
        throw new Error(
            'useAuth должен использоваться внутри AuthProvider',
        )
    }

    return context
}

function authReducer(
    _state: AuthState,
    action: AuthAction,
): AuthState {
    switch (action.type) {
        case 'LOADING':
            return {
                status: 'loading',
                user: null,
                error: null,
            }

        case 'AUTHENTICATED':
            return {
                status: 'authenticated',
                user: action.user,
                error: null,
            }

        case 'UNAUTHENTICATED':
            return {
                status: 'unauthenticated',
                user: null,
                error: null,
            }

        case 'TEMPORARILY_UNAVAILABLE':
            return {
                status:
                    'temporarily-unavailable',
                user: null,
                error: action.error,
            }

        case 'LOGOUT_UNCONFIRMED':
            return {
                status: 'logout-unconfirmed',
                user: null,
                error: action.error,
            }
    }
}

function isAbortError(error: unknown): boolean {
    return error instanceof ApiError
        && error.errorCode === 'REQUEST_ABORTED'
}
