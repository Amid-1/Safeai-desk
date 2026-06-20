// frontend/src/api/http.ts
export type ApiErrorBody = {
    timestamp?: string
    status?: number
    error?: string
    message?: string
    path?: string
    requestId?: string
    fieldErrors?: Record<string, string> | null
}

export class ApiError extends Error {
    status: number
    errorCode?: string
    path?: string
    requestId?: string
    fieldErrors?: Record<string, string> | null

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
}

export function getToken(): string | null {
    return localStorage.getItem('safeai_token')
}

export function setToken(token: string): void {
    localStorage.setItem('safeai_token', token)
}

export function clearToken(): void {
    localStorage.removeItem('safeai_token')
}

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

export async function apiRequest<T>(
    url: string,
    options: ApiRequestOptions = {}
): Promise<T> {
    const { auth = true, ...fetchOptions } = options

    const token = getToken()
    const headers = new Headers(fetchOptions.headers)

    if (!headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json')
    }

    if (auth && token) {
        headers.set('Authorization', `Bearer ${token}`)
    }

    const response = await fetch(url, {
        ...fetchOptions,
        headers,
    })

    if (!response.ok) {
        if (response.status === 401) {
            clearToken()
        }

        let body: ApiErrorBody

        try {
            body = await response.json()
        } catch {
            body = {
                status: response.status,
                error: 'HTTP_ERROR',
                message: `Request failed with status ${response.status}`,
            }
        }

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