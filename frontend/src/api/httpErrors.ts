

export type ApiErrorBody = {
    timestamp?: string
    status?: number
    /** Standard application error code used by the common API envelope. */
    error?: string
    /** Domain error code used by ChatErrorResponse and newer endpoints. */
    code?: string
    message?: string
    path?: string
    requestId?: string
    fieldErrors?: Record<string, string[]> | null
    retryAfterSeconds?: number
}


export class ApiError extends Error {
    readonly status: number
    readonly errorCode?: string
    readonly path?: string
    readonly requestId?: string
    readonly fieldErrors?: Record<string, string[]> | null
    readonly retryAfterSeconds?: number

    constructor(
        message: string,
        body: ApiErrorBody,
        status: number,
    ) {
        super(message)

        this.name = 'ApiError'
        this.status = status
        this.errorCode = body.code ?? body.error
        this.path = body.path
        this.requestId = body.requestId
        this.fieldErrors = body.fieldErrors
        this.retryAfterSeconds = body.retryAfterSeconds
    }
}


const PUBLIC_MESSAGE_ERROR_CODES = new Set([
    'VALIDATION_ERROR',
    'CONSTRAINT_VIOLATION',
    'BAD_REQUEST',
    'RATE_LIMIT_EXCEEDED',
    'TOO_MANY_REQUESTS',
    'QUOTA_EXCEEDED',
    'CHAT_QUOTA_EXCEEDED',
    'AI_QUOTA_EXCEEDED',
    'CHAT_BUSY',
    'CHAT_TURN_IN_PROGRESS',
    'IDEMPOTENCY_KEY_REUSED',
    'AI_OUTCOME_AMBIGUOUS',
    'CHAT_ACCESS_REVOKED_DURING_PROCESSING',
    'CHAT_LEASE_UNAVAILABLE',
    'CHAT_PROCESSOR_FENCED',
    'RESOURCE_CONFLICT',
    'CONFLICT',
])


export type ApiErrorPresentation = {
    message: string
    requestId?: string
}


export function getApiErrorPresentation(
    error: unknown,
    fallback: string,
): ApiErrorPresentation {
    if (!(error instanceof ApiError)) {
        return {
            message: fallback,
        }
    }

    const fieldErrorText = formatFieldErrors(
        error.fieldErrors,
    )

    const publicMessage = error.errorCode
        && PUBLIC_MESSAGE_ERROR_CODES.has(error.errorCode)
        ? error.message
        : ''

    const requestId =
        shouldShowRequestId(error)
            ? error.requestId
            : undefined

    return {
        message:
            fieldErrorText
            || publicMessage
            || fallback,
        requestId,
    }
}


export function getApiErrorMessage(
    error: unknown,
    fallback: string,
): string {
    const presentation =
        getApiErrorPresentation(
            error,
            fallback,
        )

    const requestIdPart =
        presentation.requestId
            ? ` Request ID: ${presentation.requestId}`
            : ''

    return `${
        presentation.message
    }${requestIdPart}`
}


function shouldShowRequestId(
    error: ApiError,
): boolean {
    if (!error.requestId) {
        return false
    }

    /*
     * Ожидаемые клиентские ошибки (400/409/413/429 и другие 4xx)
     * пользователь может исправить сам, поэтому технический идентификатор
     * запроса в UI только создаёт шум.
     *
     * Для 5xx Request ID полезен службе поддержки для поиска конкретного
     * запроса в backend/proxy logs.
     *
     * status === 0 используется клиентом для сетевых/transport ошибок:
     * запрос мог успеть уйти на сервер, поэтому ID также полезен
     * для диагностики.
     */
    return error.status === 0
        || error.status >= 500
}


export function parseRetryAfter(
    value: string | null,
): number | undefined {
    if (!value) {
        return undefined
    }

    const seconds = Number(value)

    if (Number.isFinite(seconds) && seconds >= 0) {
        return Math.max(1, Math.ceil(seconds))
    }

    const timestamp = Date.parse(value)

    if (!Number.isFinite(timestamp)) {
        return undefined
    }

    return Math.max(
        1,
        Math.ceil((timestamp - Date.now()) / 1000),
    )
}


function formatFieldErrors(
    fieldErrors?: Record<string, string[]> | null,
): string {
    if (!fieldErrors) {
        return ''
    }

    return Object.entries(fieldErrors)
        .flatMap(([field, messages]) =>
            messages.map(
                (message) => `${field}: ${message}`,
            ),
        )
        .join('; ')
}
