// frontend/src/features/auth/controller/useLoginPageController.ts
// Состояние входа, блокировка повторной отправки и обработка ответов API.
import { useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent, SyntheticEvent } from 'react'
import { ApiError, getApiErrorMessage } from '../../../shared/api/http'
import { useAuth } from '../state/useAuth'

type InvalidField = 'email' | 'password' | 'credentials' | null

export function useLoginPageController() {
    const {
        authStatus,
        authError,
        loginUser,
        logoutUser,
    } = useAuth()

    const emailInputRef =
        useRef<HTMLInputElement | null>(null)
    const passwordInputRef =
        useRef<HTMLInputElement | null>(null)
    const submitInFlightRef = useRef(false)

    const [email, setEmail] = useState('')
    const [password, setPassword] =
        useState('')
    const [error, setError] = useState('')
    const [invalidField, setInvalidField] =
        useState<InvalidField>(null)
    const [loading, setLoading] =
        useState(false)
    const [retryUntil, setRetryUntil] =
        useState<number | null>(null)
    const [now, setNow] = useState(
        0,
    )

    useEffect(() => {
        if (retryUntil === null) {
            return
        }

        const intervalId = window.setInterval(
            () => {
                const currentTime = Date.now()
                setNow(currentTime)
                if (currentTime >= retryUntil) {
                    window.clearInterval(intervalId)
                    setRetryUntil(null)
                }
            },
            1_000,
        )

        return () => {
            window.clearInterval(intervalId)
        }
    }, [retryUntil])

    const retryAfterSeconds = useMemo(() => {
        if (retryUntil === null) {
            return 0
        }

        return Math.max(
            0,
            Math.ceil(
                (retryUntil - now) / 1_000,
            ),
        )
    }, [
        retryUntil,
        now,
    ])

    async function handleSubmit(
        event: SyntheticEvent<HTMLFormElement>,
    ): Promise<void> {
        event.preventDefault()

        if (
            submitInFlightRef.current
            || loading
            || retryAfterSeconds > 0
        ) {
            return
        }

        setError('')
        setInvalidField(null)

        const normalizedEmail = email.trim()

        if (!normalizedEmail) {
            setInvalidField('email')
            setError('Введите email.')
            emailInputRef.current?.focus()
            return
        }

        if (!password) {
            setInvalidField('password')
            setError('Введите пароль.')
            passwordInputRef.current?.focus()
            return
        }

        submitInFlightRef.current = true
        setLoading(true)

        try {
            await loginUser({
                email: normalizedEmail,
                password,
            })
        } catch (loginError) {
            if (
                loginError instanceof ApiError
                && loginError.status === 401
            ) {
                setPassword('')
                setInvalidField('credentials')
                setError(
                    'Неверный email или пароль.',
                )

                window.requestAnimationFrame(
                    () => {
                        passwordInputRef.current
                            ?.focus()
                    },
                )
                return
            }

            if (
                loginError instanceof ApiError
                && loginError.status === 429
                && loginError.retryAfterSeconds
            ) {
                const currentTime = Date.now()

                setRetryUntil(
                    currentTime
                    + loginError.retryAfterSeconds
                    * 1_000,
                )
                setNow(currentTime)
                setError(
                    'Слишком много попыток входа.',
                )
                return
            }

            setError(
                getApiErrorMessage(
                    loginError,
                    'Не удалось выполнить вход.',
                ),
            )
        } finally {
            submitInFlightRef.current = false
            setLoading(false)
        }
    }

    async function retryLogout(): Promise<void> {
        try {
            await logoutUser()
        } catch {
            // Ошибка уже сохранена в AuthContext.
        }
    }

    function preventSubmitOnRetry(
        event: KeyboardEvent<HTMLButtonElement>,
    ): void {
        if (
            retryAfterSeconds > 0
            && (
                event.key === 'Enter'
                || event.key === ' '
            )
        ) {
            event.preventDefault()
        }
    }

    return { authStatus, authError, emailInputRef, passwordInputRef, email, setEmail,
        password, setPassword, error, invalidField, loading, retryAfterSeconds,
        handleSubmit, retryLogout, preventSubmitOnRetry }
}
