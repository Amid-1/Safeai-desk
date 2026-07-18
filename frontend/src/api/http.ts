// ============================================================
// frontend/src/api/http.ts
// ============================================================

export type ApiErrorBody = {
    timestamp?: string
    status?: number
    error?: string
    message?: string
    path?: string
    requestId?: string
    fieldErrors?: Record<string, string[]> | null
}

export class ApiError extends Error {
    readonly status: number
    readonly errorCode?: string
    readonly path?: string
    readonly requestId?: string
    readonly fieldErrors?: Record<string, string[]> | null

    constructor(message: string, body: ApiErrorBody, status: number) {
        super(message)

        this.name = 'ApiError'
        this.status = status
        this.errorCode = body.error
        this.path = body.path
        this.requestId = body.requestId
        this.fieldErrors = body.fieldErrors
    }
}

export type ApiRequestOptions = RequestInit & {
    skipRefresh?: boolean
    timeoutMs?: number
}

export type UnauthorizedReason =
    | 'access-token-refresh-rejected'
    | 'request-unauthorized'

type RefreshResult =
    | { kind: 'success' }
    | { kind: 'unauthorized'; error: ApiError }
    | { kind: 'temporary-failure'; error: ApiError }

type RequestSignalContext = {
    signal?: AbortSignal
    cleanup: () => void
}

const RAW_API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
const API_BASE_URL = RAW_API_BASE_URL.replace(/\/+$/, '')

const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'
const REQUEST_ID_HEADER_NAME = 'X-Request-Id'
const UNAUTHORIZED_EVENT_NAME = 'safeai:unauthorized'

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])
const DEFAULT_TIMEOUT_MS = 30_000
const REFRESH_TIMEOUT_MS = 20_000

let refreshPromise: Promise<RefreshResult> | null = null

export function subscribeUnauthorized(
    handler: (reason: UnauthorizedReason) => void
): () => void {
    const listener = (event: Event) => {
        if (event instanceof CustomEvent) {
            handler(event.detail?.reason ?? 'request-unauthorized')
            return
        }

        handler('request-unauthorized')
    }

    window.addEventListener(UNAUTHORIZED_EVENT_NAME, listener)

    return () => {
        window.removeEventListener(UNAUTHORIZED_EVENT_NAME, listener)
    }
}

function notifyUnauthorized(reason: UnauthorizedReason) {
    window.dispatchEvent(
        new CustomEvent(UNAUTHORIZED_EVENT_NAME, {
            detail: { reason },
        })
    )
}

export function getApiErrorMessage(error: unknown, fallback: string): string {
    if (error instanceof ApiError) {
        const fieldErrorText = formatFieldErrors(error.fieldErrors)
        const requestIdPart = error.requestId
            ? ` Request ID: ${error.requestId}`
            : ''

        return `${fieldErrorText || error.message || fallback}${requestIdPart}`
    }

    if (error instanceof Error) {
        return error.message || fallback
    }

    return fallback
}

function formatFieldErrors(
    fieldErrors?: Record<string, string[]> | null
): string {
    if (!fieldErrors) {
        return ''
    }

    return Object.entries(fieldErrors)
        .flatMap(([field, messages]) =>
            messages.map((message) => `${field}: ${message}`)
        )
        .join('; ')
}

function buildApiUrl(url: string): string {
    const isAbsoluteUrl = /^[a-z][a-z\d+\-.]*:/i.test(url)

    if (isAbsoluteUrl) {
        throw new Error(
            'Абсолютные API URL запрещены. Используйте относительный путь /api/...'
        )
    }

    if (!url.startsWith('/')) {
        throw new Error('API URL должен начинаться с /')
    }

    return `${API_BASE_URL}${url}`
}

function getCookie(name: string): string | null {
    const prefix = `${name}=`

    const cookie = document.cookie
        .split(';')
        .map((value) => value.trim())
        .find((value) => value.startsWith(prefix))

    if (!cookie) {
        return null
    }

    try {
        return decodeURIComponent(cookie.substring(prefix.length))
    } catch {
        return null
    }
}

function isUnsafeMethod(method: string): boolean {
    return !SAFE_METHODS.has(method.toUpperCase())
}

function isFormDataBody(body: BodyInit | null | undefined): boolean {
    return typeof FormData !== 'undefined' && body instanceof FormData
}

function getRequestPath(url: string): string {
    return url.split('?')[0]
}

function isAuthEndpoint(url: string): boolean {
    const path = getRequestPath(url)

    return path === '/api/auth/login'
        || path === '/api/auth/refresh'
        || path === '/api/auth/logout'
        || path === '/api/auth/csrf'
}

function createRequestId(): string {
    if (typeof crypto !== 'undefined') {
        const randomUUID = (crypto as Crypto & {
            randomUUID?: () => string
        }).randomUUID

        if (typeof randomUUID === 'function') {
            return randomUUID.call(crypto)
        }
    }

    return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function createRequestSignal(
    externalSignal: AbortSignal | null | undefined,
    timeoutMs: number | undefined
): RequestSignalContext {
    const effectiveTimeoutMs = normalizeTimeout(timeoutMs)

    if (!externalSignal && effectiveTimeoutMs === null) {
        return {
            signal: undefined,
            cleanup: () => undefined,
        }
    }

    const controller = new AbortController()
    let timeoutId: number | undefined

    const abortFromExternal = () => {
        controller.abort(externalSignal?.reason)
    }

    if (externalSignal) {
        if (externalSignal.aborted) {
            abortFromExternal()
        } else {
            externalSignal.addEventListener('abort', abortFromExternal, {
                once: true,
            })
        }
    }

    if (effectiveTimeoutMs !== null) {
        timeoutId = window.setTimeout(() => {
            controller.abort(
                new DOMException('Request timed out', 'TimeoutError')
            )
        }, effectiveTimeoutMs)
    }

    return {
        signal: controller.signal,
        cleanup: () => {
            if (timeoutId !== undefined) {
                window.clearTimeout(timeoutId)
            }

            externalSignal?.removeEventListener('abort', abortFromExternal)
        },
    }
}

function normalizeTimeout(timeoutMs: number | undefined): number | null {
    if (timeoutMs === 0) {
        return null
    }

    if (timeoutMs === undefined) {
        return DEFAULT_TIMEOUT_MS
    }

    if (!Number.isFinite(timeoutMs) || timeoutMs < 0) {
        throw new Error('timeoutMs должен быть неотрицательным числом')
    }

    return Math.max(1, Math.trunc(timeoutMs))
}

function toNetworkApiError(
    error: unknown,
    fallbackMessage: string,
    requestId?: string
): ApiError {
    if (error instanceof ApiError) {
        return error
    }

    if (error instanceof DOMException && error.name === 'AbortError') {
        return new ApiError(
            'Запрос был отменён',
            {
                status: 0,
                error: 'REQUEST_ABORTED',
                message: 'Запрос был отменён',
                requestId,
            },
            0
        )
    }

    if (error instanceof DOMException && error.name === 'TimeoutError') {
        return new ApiError(
            'Превышено время ожидания ответа сервера',
            {
                status: 0,
                error: 'REQUEST_TIMEOUT',
                message: 'Превышено время ожидания ответа сервера',
                requestId,
            },
            0
        )
    }

    return new ApiError(
        fallbackMessage,
        {
            status: 0,
            error: 'NETWORK_ERROR',
            message: fallbackMessage,
            requestId,
        },
        0
    )
}

async function fetchWithApiError(
    url: string,
    init: RequestInit,
    timeoutMs: number
): Promise<Response> {
    const requestUrl = buildApiUrl(url)

    const requestId =
        new Headers(init.headers).get(REQUEST_ID_HEADER_NAME)
        ?? undefined

    const signalContext = createRequestSignal(
        init.signal,
        timeoutMs
    )

    try {
        return await fetch(requestUrl, {
            ...init,
            signal: signalContext.signal,
        })
    } catch (error) {
        throw toNetworkApiError(
            error,
            'Не удалось связаться с сервером',
            requestId
        )
    } finally {
        signalContext.cleanup()
    }
}

export async function ensureCsrfToken(): Promise<void> {
    const headers = new Headers()
    headers.set(REQUEST_ID_HEADER_NAME, createRequestId())

    const response = await fetchWithApiError(
        '/api/auth/csrf',
        {
            method: 'GET',
            headers,
            credentials: 'include',
        },
        REFRESH_TIMEOUT_MS
    )

    if (!response.ok) {
        throw await createApiErrorFromResponse(
            response,
            'Не удалось получить CSRF-токен'
        )
    }

    // Читаем тело, чтобы выявить некорректный 2xx-ответ.
    await parseSuccessBody<unknown>(response)
}

async function doRefreshAccessToken(
    retryAfterCsrfRefresh = true
): Promise<RefreshResult> {
    let csrfToken = getCookie(CSRF_COOKIE_NAME)

    if (!csrfToken) {
        try {
            await ensureCsrfToken()
            csrfToken = getCookie(CSRF_COOKIE_NAME)
        } catch (error) {
            return {
                kind: 'temporary-failure',
                error: toNetworkApiError(
                    error,
                    'Не удалось подготовить обновление сессии'
                ),
            }
        }
    }

    const headers = new Headers()
    headers.set(REQUEST_ID_HEADER_NAME, createRequestId())

    if (csrfToken) {
        headers.set(CSRF_HEADER_NAME, csrfToken)
    }

    let response: Response

    try {
        response = await fetchWithApiError(
            '/api/auth/refresh',
            {
                method: 'POST',
                headers,
                credentials: 'include',
            },
            REFRESH_TIMEOUT_MS
        )
    } catch (error) {
        return {
            kind: 'temporary-failure',
            error: toNetworkApiError(
                error,
                'Не удалось обновить сессию'
            ),
        }
    }

    if (response.ok) {
        return { kind: 'success' }
    }

    if (response.status === 403 && retryAfterCsrfRefresh) {
        try {
            await ensureCsrfToken()
        } catch (error) {
            return {
                kind: 'temporary-failure',
                error: toNetworkApiError(
                    error,
                    'Не удалось обновить CSRF-токен'
                ),
            }
        }

        return doRefreshAccessToken(false)
    }

    const error = await createApiErrorFromResponse(
        response,
        'Не удалось обновить сессию'
    )

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

async function refreshAccessToken(): Promise<RefreshResult> {
    if (!refreshPromise) {
        refreshPromise = doRefreshAccessToken()
            .finally(() => {
                refreshPromise = null
            })
    }

    return refreshPromise
}

async function parseErrorBody(response: Response): Promise<ApiErrorBody> {
    const responseRequestId =
        response.headers.get(REQUEST_ID_HEADER_NAME) ?? undefined

    try {
        const text = await response.text()

        if (!text) {
            return {
                status: response.status,
                error: 'HTTP_ERROR',
                message: `Запрос завершился с кодом ${response.status}`,
                requestId: responseRequestId,
            }
        }

        const parsed = JSON.parse(text)

        if (!isRecord(parsed)) {
            throw new Error('Error response is not an object')
        }

        return {
            timestamp: asOptionalString(parsed.timestamp),
            status: asOptionalNumber(parsed.status) ?? response.status,
            error: asOptionalString(parsed.error),
            message: asOptionalString(parsed.message),
            path: asOptionalString(parsed.path),
            requestId: asOptionalString(parsed.requestId) ?? responseRequestId,
            fieldErrors: parseFieldErrors(parsed.fieldErrors),
        }
    } catch {
        return {
            status: response.status,
            error: 'INVALID_ERROR_RESPONSE',
            message: `Сервер вернул некорректный ответ с кодом ${response.status}`,
            requestId: responseRequestId,
        }
    }
}

async function parseSuccessBody<T>(response: Response): Promise<T> {
    if (response.status === 204 || response.status === 205) {
        return undefined as T
    }

    const text = await response.text()

    if (!text) {
        return undefined as T
    }

    try {
        return JSON.parse(text) as T
    } catch {
        throw new ApiError(
            'Сервер вернул некорректный JSON-ответ',
            {
                status: response.status,
                error: 'INVALID_RESPONSE',
                message: 'Сервер вернул некорректный JSON-ответ',
                requestId:
                    response.headers.get(REQUEST_ID_HEADER_NAME) ?? undefined,
            },
            response.status
        )
    }
}

async function createApiErrorFromResponse(
    response: Response,
    fallbackMessage: string
): Promise<ApiError> {
    const body = await parseErrorBody(response)

    return new ApiError(
        body.message || fallbackMessage,
        body,
        response.status
    )
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object'
        && value !== null
        && !Array.isArray(value)
}

function asOptionalString(value: unknown): string | undefined {
    return typeof value === 'string' ? value : undefined
}

function asOptionalNumber(value: unknown): number | undefined {
    return typeof value === 'number' && Number.isFinite(value)
        ? value
        : undefined
}

function parseFieldErrors(
    value: unknown
): Record<string, string[]> | null | undefined {
    if (value === null) {
        return null
    }

    if (!isRecord(value)) {
        return undefined
    }

    const result: Record<string, string[]> = {}

    Object.entries(value).forEach(([field, messages]) => {
        if (!Array.isArray(messages)) {
            return
        }

        const normalizedMessages = messages.filter(
            (message): message is string => typeof message === 'string'
        )

        if (normalizedMessages.length > 0) {
            result[field] = normalizedMessages
        }
    })

    return result
}

export async function apiRequest<T>(
    url: string,
    options: ApiRequestOptions = {}
): Promise<T> {
    const {
        skipRefresh = false,
        timeoutMs,
        ...fetchOptions
    } = options

    const method = (fetchOptions.method ?? 'GET').toUpperCase()
    const headers = new Headers(fetchOptions.headers)
    const body = fetchOptions.body

    headers.set(REQUEST_ID_HEADER_NAME, createRequestId())

    if (body && !headers.has('Content-Type') && !isFormDataBody(body)) {
        headers.set('Content-Type', 'application/json')
    }

    if (isUnsafeMethod(method)) {
        let csrfToken = getCookie(CSRF_COOKIE_NAME)

        if (!csrfToken) {
            await ensureCsrfToken()
            csrfToken = getCookie(CSRF_COOKIE_NAME)
        }

        if (csrfToken) {
            headers.set(CSRF_HEADER_NAME, csrfToken)
        }
    }

    const response = await fetchWithApiError(
        url,
        {
            ...fetchOptions,
            method,
            headers,
            credentials: 'include',
        },
        timeoutMs ?? DEFAULT_TIMEOUT_MS
    )

    if (
        response.status === 401
        && !skipRefresh
        && !isAuthEndpoint(url)
    ) {
        const refreshResult = await refreshAccessToken()

        if (refreshResult.kind === 'success') {
            return apiRequest<T>(url, {
                ...options,
                skipRefresh: true,
            })
        }

        if (refreshResult.kind === 'unauthorized') {
            notifyUnauthorized('access-token-refresh-rejected')
        }

        throw refreshResult.error
    }

    if (!response.ok) {
        const error = await createApiErrorFromResponse(
            response,
            `Запрос завершился с кодом ${response.status}`
        )

        if (response.status === 401 && !isAuthEndpoint(url)) {
            notifyUnauthorized('request-unauthorized')
        }

        throw error
    }

    return parseSuccessBody<T>(response)
}