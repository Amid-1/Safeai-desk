// ============================================================
// frontend/src/shared/api/http/httpPaths.ts
// ============================================================

import { SAFE_METHODS } from './httpConfig'

const RAW_API_BASE_PATH =
    import.meta.env.VITE_API_BASE_URL ?? ''

const API_BASE_PATH = validateApiBasePath(
    RAW_API_BASE_PATH,
)

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

export function isUnsafeMethod(method: string): boolean {
    return !SAFE_METHODS.has(
        method.toUpperCase(),
    )
}

export function isAuthEndpoint(path: string): boolean {
    const requestPath = path.split(/[?#]/, 1)[0]

    return requestPath === '/api/auth/login'
        || requestPath === '/api/auth/refresh'
        || requestPath === '/api/auth/logout'
        || requestPath === '/api/auth/csrf'
}
