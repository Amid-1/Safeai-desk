import { ApiError } from './httpErrors'
import { parseSuccessBlob, parseSuccessBody, createApiErrorFromResponse } from './httpResponseSupport'
import {
    awaitWithDeadline,
    createAbortedApiError,
    createDeadline,
    createRequestSignal,
    createTimeoutApiError,
    normalizeTimeout,
    toCoordinationApiError,
    toNetworkApiError,
} from './httpRequestSupport'

export {
    ApiError,
    getApiErrorMessage,
    getApiErrorPresentation,
    parseRetryAfter,
} from './httpErrors'
export type { ApiErrorBody, ApiErrorPresentation } from './httpErrors'

import {
    publishAuthEvent,
    runWithAuthRefreshLock,
} from './authCoordinator'


import {
    createSecureUuid,
} from '../utils/secureUuid'


export type ApiResponseType =
    | 'json'
    | 'blob'


export type ApiRequestOptions = Omit<
    RequestInit,
    'body' | 'credentials' | 'mode'
> & {
    json?: unknown
    body?: BodyInit | null
    skipRefresh?: boolean
    timeoutMs?: number
    responseType?: ApiResponseType
}


export type UnauthorizedReason =
    | 'access-token-refresh-rejected'
    | 'request-unauthorized'


type RefreshResult =
    | { kind: 'success' }
    | { kind: 'unauthorized'; error: ApiError }
    | { kind: 'temporary-failure'; error: ApiError }


type ApiRequestContext = {
    deadline: number | null
    requestId: string
    allowRefresh: boolean
    allowCsrfRetry: boolean
}


export const API_TIMEOUTS = {
    default: 30_000,
    auth: 20_000,

    // Немного больше Nginx proxy timeout 90s, чтобы frontend получил
    // контролируемый proxy/backend response.
    chat: 95_000,

    report: 30_000,
    download: 120_000,
} as const


const RAW_API_BASE_PATH =
    import.meta.env.VITE_API_BASE_URL ?? ''


const API_BASE_PATH = validateApiBasePath(
    RAW_API_BASE_PATH,
)


const CSRF_COOKIE_NAME = 'XSRF-TOKEN'

const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'

const REQUEST_ID_HEADER_NAME = 'X-Request-Id'

const UNAUTHORIZED_EVENT_NAME = 'safeai:unauthorized'


const SAFE_METHODS = new Set([
    'GET',
    'HEAD',
    'OPTIONS',
])


const CSRF_RETRYABLE_ERROR_CODES = new Set([
    'CSRF_TOKEN_INVALID',
    'CSRF_TOKEN_EXPIRED',
])


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


export function validateApiBasePath(
    value: string,
): string {
    const normalized = value.trim()

    if (!normalized) {
        return ''
    }

    const resolved = new URL(
        normalized,
        window.location.origin,
    )

    if (
        resolved.origin !== window.location.origin
        || resolved.search
        || resolved.hash
        || resolved.username
        || resolved.password
    ) {
        throw new Error(
            'VITE_API_BASE_URL должен быть same-origin path',
        )
    }

    return resolved.pathname.replace(/\/+$/, '')
}


export function buildApiUrl(path: string): string {
    if (path !== path.trim()) {
        throw new Error(
            'API path не должен содержать внешние пробелы',
        )
    }

    if (
        path !== '/api'
        && !path.startsWith('/api/')
    ) {
        throw new Error(
            'API path должен начинаться с /api/',
        )
    }

    const expectedPrefix = `${API_BASE_PATH}/api`

    const resolved = new URL(
        `${API_BASE_PATH}${path}`,
        window.location.origin,
    )

    if (
        resolved.origin !== window.location.origin
        || resolved.hash
        || (
            resolved.pathname !== expectedPrefix
            && !resolved.pathname.startsWith(
                `${expectedPrefix}/`,
            )
        )
    ) {
        throw new Error('Некорректный API URL')
    }

    return `${resolved.pathname}${resolved.search}`
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


export async function apiRequest<T>(
    path: string,
    options: ApiRequestOptions = {},
): Promise<T> {
    const timeoutMs = normalizeTimeout(
        options.timeoutMs,
    )

    const context: ApiRequestContext = {
        deadline: timeoutMs === null
            ? null
            : Date.now() + timeoutMs,
        requestId: createRequestId(),
        allowRefresh: !options.skipRefresh,
        allowCsrfRetry: true,
    }

    return apiRequestInternal<T>(
        path,
        options,
        context,
    )
}


async function apiRequestInternal<T>(
    path: string,
    options: ApiRequestOptions,
    context: ApiRequestContext,
): Promise<T> {
    const {
        json,
        body: suppliedBody,
        skipRefresh: _skipRefresh,
        timeoutMs: _timeoutMs,
        responseType = 'json',
        ...requestInit
    } = options

    if (
        json !== undefined
        && suppliedBody !== undefined
        && suppliedBody !== null
    ) {
        throw new Error(
            'Нельзя одновременно передавать json и body',
        )
    }

    let body = suppliedBody
    const headers = new Headers(requestInit.headers)
    const method = (
        requestInit.method ?? 'GET'
    ).toUpperCase()

    if (json !== undefined) {
        body = JSON.stringify(json)

        if (!headers.has('Content-Type')) {
            headers.set(
                'Content-Type',
                'application/json',
            )
        }
    }

    if (
        body instanceof ReadableStream
        && context.allowRefresh
    ) {
        throw new ApiError(
            'Streaming body нельзя автоматически повторить после refresh',
            {
                status: 0,
                error: 'NON_REPLAYABLE_BODY',
                message:
                    'Streaming body нельзя автоматически повторить после refresh',
                requestId: context.requestId,
            },
            0,
        )
    }

    headers.set(
        REQUEST_ID_HEADER_NAME,
        context.requestId,
    )

    if (isUnsafeMethod(method)) {
        const csrfToken = await requireCsrfToken(
            context.deadline,
        )

        headers.set(
            CSRF_HEADER_NAME,
            csrfToken,
        )
    }

    const response = await fetchWithApiError(
        path,
        {
            ...requestInit,
            method,
            headers,
            body,
        },
        context.deadline,
    )

    if (
        response.status === 401
        && context.allowRefresh
        && !isAuthEndpoint(path)
    ) {
        const refreshResult = await refreshAccessToken(
            context.deadline,
        )

        if (refreshResult.kind === 'success') {
            return apiRequestInternal<T>(
                path,
                options,
                {
                    ...context,
                    allowRefresh: false,
                },
            )
        }

        if (refreshResult.kind === 'unauthorized') {
            notifyUnauthorized(
                'access-token-refresh-rejected',
            )
        }

        throw refreshResult.error
    }

    if (!response.ok) {
        const error = await createApiErrorFromResponse(
            response,
            `Запрос завершился с кодом ${response.status}`,
        )

        if (
            response.status === 403
            && context.allowCsrfRetry
            && isUnsafeMethod(method)
            && error.errorCode
            && CSRF_RETRYABLE_ERROR_CODES.has(
                error.errorCode,
            )
        ) {
            await fetchCsrfToken(
                context.deadline,
                false,
            )

            return apiRequestInternal<T>(
                path,
                options,
                {
                    ...context,
                    allowCsrfRetry: false,
                },
            )
        }

        if (
            response.status === 401
            && !isAuthEndpoint(path)
        ) {
            notifyUnauthorized(
                'request-unauthorized',
            )
        }

        throw error
    }

    if (responseType === 'blob') {
        return await parseSuccessBlob(
            response,
        ) as T
    }

    return parseSuccessBody<T>(response)
}


async function requireCsrfToken(
    deadline: number | null,
): Promise<string> {
    const existingToken = getCookie(CSRF_COOKIE_NAME)

    if (existingToken) {
        return existingToken
    }

    return fetchCsrfToken(deadline, false)
}


async function fetchCsrfToken(
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


async function refreshAccessToken(
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


async function fetchWithApiError(
    path: string,
    init: RequestInit,
    deadline: number | null,
): Promise<Response> {
    const requestUrl = buildApiUrl(path)
    const requestId = new Headers(
        init.headers,
    ).get(REQUEST_ID_HEADER_NAME) ?? undefined

    const signalContext = createRequestSignal(
        init.signal,
        deadline,
    )

    try {
        return await fetch(requestUrl, {
            ...init,
            mode: 'same-origin',
            credentials: 'include',
            signal: signalContext.signal,
        })
    } catch (error) {
        if (signalContext.timedOut()) {
            throw createTimeoutApiError(requestId)
        }

        if (signalContext.externallyAborted()) {
            throw createAbortedApiError(requestId)
        }

        throw toNetworkApiError(
            error,
            'Не удалось связаться с сервером',
            requestId,
        )
    } finally {
        signalContext.cleanup()
    }
}


function notifyUnauthorized(
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


function isUnsafeMethod(method: string): boolean {
    return !SAFE_METHODS.has(
        method.toUpperCase(),
    )
}


function isAuthEndpoint(path: string): boolean {
    const requestPath = path.split(/[?#]/, 1)[0]

    return requestPath === '/api/auth/login'
        || requestPath === '/api/auth/refresh'
        || requestPath === '/api/auth/logout'
        || requestPath === '/api/auth/csrf'
}


function createRequestId(): string {
    return createSecureUuid()
}
