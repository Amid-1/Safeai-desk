// ============================================================
// frontend/src/api/http/httpAuthFlow.ts
// ============================================================

import { ApiError } from '../httpErrors'
import { parseSuccessBody, createApiErrorFromResponse } from '../httpResponseSupport'
import { awaitWithDeadline, createDeadline, toCoordinationApiError, toNetworkApiError } from '../httpRequestSupport'
import { publishAuthEvent, runWithAuthRefreshLock } from '../authCoordinator'
import { API_TIMEOUTS, CSRF_COOKIE_NAME, CSRF_HEADER_NAME, REQUEST_ID_HEADER_NAME, UNAUTHORIZED_EVENT_NAME, CSRF_RETRYABLE_ERROR_CODES } from './httpConfig'
import { fetchWithApiError, createRequestId } from './httpTransport'

export type UnauthorizedReason =
    | 'access-token-refresh-rejected'
    | 'request-unauthorized'

type RefreshResult =
    | { kind: 'success' }
    | { kind: 'unauthorized'; error: ApiError }
    | { kind: 'temporary-failure'; error: ApiError }

let refreshPromise: Promise<RefreshResult> | null = null

export function subscribeUnauthorized(
    handler: (reason: UnauthorizedReason) => void,
): () => void {
    const listener = (event: Event) => {
        if (event instanceof CustomEvent) {
            handler(
                event.detail?.reason
                ?? 'request-unauthorized',
            )
            return
        }

        handler('request-unauthorized')
    }

    window.addEventListener(
        UNAUTHORIZED_EVENT_NAME,
        listener,
    )

    return () => {
        window.removeEventListener(
            UNAUTHORIZED_EVENT_NAME,
            listener,
        )
    }
}

export async function ensureCsrfToken(): Promise<string> {
    const existingToken = getCookie(CSRF_COOKIE_NAME)

    if (existingToken) {
        return existingToken
    }

    return fetchCsrfToken(
        createDeadline(API_TIMEOUTS.auth),
        false,
    )
}

export async function rotateCsrfToken(
    previousToken?: string | null,
): Promise<string> {
    return fetchCsrfToken(
        createDeadline(API_TIMEOUTS.auth),
        true,
        previousToken,
    )
}

export async function requireCsrfToken(
    deadline: number | null,
): Promise<string> {
    const existingToken = getCookie(CSRF_COOKIE_NAME)

    if (existingToken) {
        return existingToken
    }

    return fetchCsrfToken(deadline, false)
}

export async function fetchCsrfToken(
    deadline: number | null,
    requireRotation: boolean,
    rotationBaseline?: string | null,
): Promise<string> {
    const previousToken = rotationBaseline
        ?? getCookie(CSRF_COOKIE_NAME)

    const headers = new Headers()

    headers.set(
        REQUEST_ID_HEADER_NAME,
        createRequestId(),
    )

    const response = await fetchWithApiError(
        '/api/auth/csrf',
        {
            method: 'GET',
            headers,
        },
        deadline,
    )

    if (!response.ok) {
        throw await createApiErrorFromResponse(
            response,
            'Не удалось получить CSRF-токен',
        )
    }

    await parseSuccessBody<unknown>(response)

    const token = getCookie(CSRF_COOKIE_NAME)

    if (!token) {
        throw new ApiError(
            'Сервер не установил CSRF cookie',
            {
                status: 0,
                error: 'CSRF_TOKEN_MISSING',
                message:
                    'Сервер не установил CSRF cookie',
            },
            0,
        )
    }

    if (
        requireRotation
        && previousToken
        && previousToken === token
    ) {
        throw new ApiError(
            'Сервер не выполнил ротацию CSRF-токена',
            {
                status: 0,
                error: 'CSRF_TOKEN_NOT_ROTATED',
                message:
                    'Сервер не выполнил ротацию CSRF-токена',
            },
            0,
        )
    }

    return token
}

export async function refreshAccessToken(
    deadline: number | null,
): Promise<RefreshResult> {
    if (!refreshPromise) {
        refreshPromise = runWithAuthRefreshLock(
            deadline,
            async () => {
                const probeResult = await probeAccessToken(
                    deadline,
                )

                if (probeResult.kind === 'authorized') {
                    return {
                        kind: 'success',
                    } satisfies RefreshResult
                }

                if (
                    probeResult.kind === 'temporary-failure'
                ) {
                    return probeResult
                }

                const result = await doRefreshAccessToken(
                    deadline,
                )

                if (result.kind === 'success') {
                    publishAuthEvent(
                        'REFRESH_SUCCEEDED',
                    )
                }

                return result
            },
        )
            .catch((error: unknown) => ({
                kind: 'temporary-failure',
                error: toCoordinationApiError(error),
            }) satisfies RefreshResult)
            .finally(() => {
                refreshPromise = null
            })
    }

    return awaitWithDeadline(
        refreshPromise,
        deadline,
    )
}

async function probeAccessToken(
    deadline: number | null,
): Promise<
    | { kind: 'authorized' }
    | { kind: 'unauthorized' }
    | {
        kind: 'temporary-failure'
        error: ApiError
    }
> {
    const headers = new Headers()

    headers.set(
        REQUEST_ID_HEADER_NAME,
        createRequestId(),
    )

    let response: Response

    try {
        response = await fetchWithApiError(
            '/api/auth/me',
            {
                method: 'GET',
                headers,
            },
            deadline,
        )
    } catch (error) {
        return {
            kind: 'temporary-failure',
            error: toNetworkApiError(
                error,
                'Не удалось проверить access token',
            ),
        }
    }

    if (response.ok) {
        return { kind: 'authorized' }
    }

    if (response.status === 401) {
        return { kind: 'unauthorized' }
    }

    return {
        kind: 'temporary-failure',
        error: await createApiErrorFromResponse(
            response,
            'Не удалось проверить access token',
        ),
    }
}

async function doRefreshAccessToken(
    deadline: number | null,
    allowCsrfRetry = true,
): Promise<RefreshResult> {
    let csrfToken: string

    try {
        csrfToken = await requireCsrfToken(deadline)
    } catch (error) {
        return {
            kind: 'temporary-failure',
            error: toNetworkApiError(
                error,
                'Не удалось подготовить обновление сессии',
            ),
        }
    }

    const headers = new Headers()

    headers.set(
        REQUEST_ID_HEADER_NAME,
        createRequestId(),
    )
    headers.set(
        CSRF_HEADER_NAME,
        csrfToken,
    )

    let response: Response

    try {
        response = await fetchWithApiError(
            '/api/auth/refresh',
            {
                method: 'POST',
                headers,
            },
            deadline,
        )
    } catch (error) {
        return {
            kind: 'temporary-failure',
            error: toNetworkApiError(
                error,
                'Не удалось обновить сессию',
            ),
        }
    }

    if (response.ok) {
        try {
            await fetchCsrfToken(
                deadline,
                false,
            )
        } catch (error) {
            return {
                kind: 'temporary-failure',
                error: toNetworkApiError(
                    error,
                    'Сессия обновлена, но CSRF-токен не подтверждён',
                ),
            }
        }

        return { kind: 'success' }
    }

    const error = await createApiErrorFromResponse(
        response,
        'Не удалось обновить сессию',
    )

    if (
        response.status === 403
        && allowCsrfRetry
        && error.errorCode
        && CSRF_RETRYABLE_ERROR_CODES.has(
            error.errorCode,
        )
    ) {
        try {
            await fetchCsrfToken(
                deadline,
                false,
            )
        } catch (csrfError) {
            return {
                kind: 'temporary-failure',
                error: toNetworkApiError(
                    csrfError,
                    'Не удалось обновить CSRF-токен',
                ),
            }
        }

        return doRefreshAccessToken(
            deadline,
            false,
        )
    }

    if (response.status === 401) {
        return {
            kind: 'unauthorized',
            error,
        }
    }

    return {
        kind: 'temporary-failure',
        error,
    }
}

export function notifyUnauthorized(
    reason: UnauthorizedReason,
): void {
    window.dispatchEvent(
        new CustomEvent(
            UNAUTHORIZED_EVENT_NAME,
            {
                detail: { reason },
            },
        ),
    )

    publishAuthEvent('SESSION_REJECTED')
}

function getCookie(name: string): string | null {
    const prefix = `${name}=`

    const cookie = document.cookie
        .split(';')
        .map((value) => value.trim())
        .find((value) =>
            value.startsWith(prefix),
        )

    if (!cookie) {
        return null
    }

    try {
        return decodeURIComponent(
            cookie.substring(prefix.length),
        )
    } catch {
        return null
    }
}
