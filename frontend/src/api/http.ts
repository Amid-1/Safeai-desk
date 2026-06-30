// frontend/src/api/http.ts

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
    status: number
    errorCode?: string
    path?: string
    requestId?: string
    fieldErrors?: Record<string, string[]> | null

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

type ApiRequestOptions = RequestInit & {
    skipRefresh?: boolean
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'
const UNAUTHORIZED_EVENT_NAME = 'safeai:unauthorized'

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

let refreshPromise: Promise<boolean> | null = null

export function subscribeUnauthorized(handler: () => void): () => void {
    window.addEventListener(UNAUTHORIZED_EVENT_NAME, handler)

    return () => {
        window.removeEventListener(UNAUTHORIZED_EVENT_NAME, handler)
    }
}

function notifyUnauthorized() {
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT_NAME))
}

export function getApiErrorMessage(err: unknown, fallback: string): string {
    if (err instanceof ApiError) {
        const fieldErrorText = formatFieldErrors(err.fieldErrors)
        const requestIdPart = err.requestId ? ` Request ID: ${err.requestId}` : ''

        return `${fieldErrorText || err.message || fallback}${requestIdPart}`
    }

    if (err instanceof Error) {
        return err.message || fallback
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
        throw new Error('Absolute API URLs are not allowed. Use relative /api/... paths')
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

    return decodeURIComponent(cookie.substring(prefix.length))
}

function isUnsafeMethod(method: string): boolean {
    return !SAFE_METHODS.has(method.toUpperCase())
}

function isFormDataBody(body: BodyInit | null | undefined): boolean {
    return typeof FormData !== 'undefined' && body instanceof FormData
}

function isAuthEndpoint(url: string): boolean {
    return url.includes('/api/auth/login')
        || url.includes('/api/auth/refresh')
        || url.includes('/api/auth/logout')
        || url.includes('/api/auth/csrf')
}

export async function ensureCsrfToken(): Promise<void> {
    const response = await fetch(buildApiUrl('/api/auth/csrf'), {
        method: 'GET',
        credentials: 'include',
    })

    if (!response.ok) {
        const body = await parseErrorBody(response)

        throw new ApiError(
            body.message || `CSRF request failed with status ${response.status}`,
            body,
            response.status
        )
    }
}

async function doRefreshAccessToken(): Promise<boolean> {
    let csrfToken = getCookie(CSRF_COOKIE_NAME)

    if (!csrfToken) {
        try {
            await ensureCsrfToken()
            csrfToken = getCookie(CSRF_COOKIE_NAME)
        } catch {
            return false
        }
    }

    const headers = new Headers()

    if (csrfToken) {
        headers.set(CSRF_HEADER_NAME, csrfToken)
    }

    const response = await fetch(buildApiUrl('/api/auth/refresh'), {
        method: 'POST',
        headers,
        credentials: 'include',
    })

    return response.ok
}

async function refreshAccessToken(): Promise<boolean> {
    if (!refreshPromise) {
        refreshPromise = doRefreshAccessToken()
            .finally(() => {
                refreshPromise = null
            })
    }

    return refreshPromise
}

async function parseErrorBody(response: Response): Promise<ApiErrorBody> {
    try {
        return await response.json()
    } catch {
        return {
            status: response.status,
            error: 'HTTP_ERROR',
            message: `Request failed with status ${response.status}`,
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

    return JSON.parse(text) as T
}

export async function apiRequest<T>(
    url: string,
    options: ApiRequestOptions = {}
): Promise<T> {
    const {
        skipRefresh = false,
        ...fetchOptions
    } = options

    const method = (fetchOptions.method ?? 'GET').toUpperCase()
    const headers = new Headers(fetchOptions.headers)
    const body = fetchOptions.body

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

    const response = await fetch(buildApiUrl(url), {
        ...fetchOptions,
        method,
        headers,
        credentials: 'include',
    })

    if (
        response.status === 401
        && !skipRefresh
        && !isAuthEndpoint(url)
    ) {
        const refreshed = await refreshAccessToken()

        if (refreshed) {
            return apiRequest<T>(url, {
                ...options,
                skipRefresh: true,
            })
        }

        notifyUnauthorized()

        throw new ApiError(
            'Сессия истекла. Войдите снова.',
            {
                status: 401,
                error: 'UNAUTHORIZED',
                message: 'Сессия истекла. Войдите снова.',
            },
            401
        )
    }

    if (!response.ok) {
        const body = await parseErrorBody(response)

        if (response.status === 401 && !isAuthEndpoint(url)) {
            notifyUnauthorized()
        }

        throw new ApiError(
            body.message || `Request failed with status ${response.status}`,
            body,
            response.status
        )
    }

    return parseSuccessBody<T>(response)
}