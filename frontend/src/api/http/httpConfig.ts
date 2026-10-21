// ============================================================
// frontend/src/api/http/httpConfig.ts
// ============================================================



export const API_TIMEOUTS = {
    default: 30_000,
    auth: 20_000,

    // Немного больше Nginx proxy timeout 90s, чтобы frontend получил
    // контролируемый proxy/backend response.
    chat: 95_000,

    report: 30_000,
    download: 120_000,
} as const

export const CSRF_COOKIE_NAME = 'XSRF-TOKEN'

export const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'

export const REQUEST_ID_HEADER_NAME = 'X-Request-Id'

export const UNAUTHORIZED_EVENT_NAME = 'safeai:unauthorized'

export const SAFE_METHODS = new Set([
    'GET',
    'HEAD',
    'OPTIONS',
])

export const CSRF_RETRYABLE_ERROR_CODES = new Set([
    'CSRF_TOKEN_INVALID',
    'CSRF_TOKEN_EXPIRED',
])
