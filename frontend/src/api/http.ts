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
    auth?: boolean
    skipRefresh?: boolean
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

export function getApiErrorMessage(err: unknown, fallback: string): string {
    if (err instanceof ApiError) {
        const requestIdPart = err.requestId ? ` Request ID: ${err.requestId}` : ''
        return `${err.message}${requestIdPart}`
    }

    if (err instanceof Error) {
        return err.message || fallback
    }

    return fallback
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
        let body: ApiErrorBody

        try {
            body = await response.json()
        } catch {
            body = {
                status: response.status,
                error: 'CSRF_ERROR',
                message: `CSRF request failed with status ${response.status}`,
            }
        }

        throw new ApiError(
            body.message || `CSRF request failed with status ${response.status}`,
            body,
            response.status
        )
    }
}

async function refreshAccessToken(): Promise<boolean> {
    const csrfToken = getCookie(CSRF_COOKIE_NAME)

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

export async function apiRequest<T>(
    url: string,
    options: ApiRequestOptions = {}
): Promise<T> {
    const {
        skipRefresh = false,
        auth: _auth,
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
    }

    if (!response.ok) {
        const body = await parseErrorBody(response)

        throw new ApiError(
            body.message || `Request failed with status ${response.status}`,
            body,
            response.status
        )
    }

    if (response.status === 204) {
        return undefined as T
    }

    return await response.json() as T
}