// frontend/src/shared/api/httpErrors.ts
// ============================================================
// frontend/src/shared/api/httpErrors.ts
// ============================================================


export type ApiErrorBody = {
    timestamp?: string
    status?: number
    /** Код ошибки из общего серверного ответа. */
    error?: string
    /** Доменный код ошибки из ChatErrorResponse и новых API. */
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
        this.retryAfterSeconds = normalizeRetryAfterSeconds(body.retryAfterSeconds)
    }
}


const PUBLIC_MESSAGE_ERROR_CODES = new Set([
    'VALIDATION_ERROR',
    'CONSTRAINT_VIOLATION',
    'BAD_REQUEST',
    'QUOTA_EXCEEDED',
    'CHAT_QUOTA_EXCEEDED',
    'AI_QUOTA_EXCEEDED',
    'RESOURCE_CONFLICT',
    'CONFLICT',
])

// Точные коды backend и HTTP-статусы. Текст не зависит от ответа провайдера.
const PUBLIC_CODE_MESSAGES: Readonly<Record<string, readonly [number, string]>> = {
    RATE_LIMIT_EXCEEDED: [429, 'Слишком много запросов. Повторите попытку позже.'],
    AI_PROVIDER_RATE_LIMITED: [429, 'AI временно перегружен. Повторите попытку позже.'],
    RATE_LIMIT_UNAVAILABLE: [503, 'Сервис временно недоступен. Повторите попытку позже.'],
    AUTH_SERVICE_UNAVAILABLE: [503, 'Вход временно недоступен. Повторите попытку позже.'],
    BUDGET_RATE_UNAVAILABLE: [503, 'Не удалось подтвердить стоимость. Повторите попытку позже.'],
    AI_EXECUTION_RECONCILIATION_REQUIRED: [409, 'Проверьте состояние отправленного запроса.'],
    AI_OUTCOME_AMBIGUOUS: [409, 'Не удалось подтвердить результат запроса.'],
    CHAT_PROCESSOR_FENCED: [409, 'Не удалось подтвердить результат запроса.'],
    IDEMPOTENCY_KEY_REUSED: [409, 'Не удалось повторить отправку. Проверьте исходный запрос.'],
    CHAT_TURN_IN_PROGRESS: [409, 'Запрос ещё обрабатывается.'],
    CHAT_BUSY: [409, 'Запрос ещё обрабатывается.'],
    CHAT_LEASE_UNAVAILABLE: [503, 'Сервис временно недоступен. Проверьте состояние запроса.'],
    CHAT_LOCK_UNAVAILABLE: [503, 'Сервис временно недоступен. Проверьте состояние запроса.'],
    CHAT_ACCESS_REVOKED_DURING_PROCESSING: [403, 'Доступ к чату был отозван.'],
    USER_VERSION_CONFLICT: [409, 'Данные пользователя изменились. Обновите карточку.'],
    ORGANIZATION_VERSION_CONFLICT: [409, 'Данные организации изменились. Обновите карточку.'],
    AI_PROVIDER_TIMEOUT: [504, 'AI не ответил вовремя. Проверьте состояние запроса.'],
    AI_PROVIDER_OVERLOADED: [503, 'AI временно перегружен. Проверьте состояние запроса.'],
    AI_PROVIDER_UNAVAILABLE: [503, 'AI временно недоступен. Проверьте состояние запроса.'],
    AI_PROVIDER_ERROR: [502, 'Не удалось получить ответ AI. Проверьте состояние запроса.'],
}

export const DEFAULT_RETRY_AFTER_SECONDS = 30

// Безопасное сложение миллисекунд с любым актуальным временем браузера.
const MAX_RETRY_SECONDS = Math.floor(Number.MAX_SAFE_INTEGER / 2_000)

export function normalizeRetryAfterSeconds(value: unknown): number | undefined {
    if (typeof value !== 'number' || !Number.isFinite(value)
        || value < 0 || value > MAX_RETRY_SECONDS) {
        return undefined
    }
    return Math.max(1, Math.ceil(value))
}

export function rateLimitRetrySeconds(error: ApiError): number {
    return error.retryAfterSeconds ?? DEFAULT_RETRY_AFTER_SECONDS
}

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

    const clientError = error.status >= 400 && error.status < 500
    const fieldErrorText = error.status === 400 || error.status === 422
        ? formatFieldErrors(error.fieldErrors) : ''
    const known = error.errorCode && Object.hasOwn(PUBLIC_CODE_MESSAGES, error.errorCode)
        ? PUBLIC_CODE_MESSAGES[error.errorCode] : undefined
    const fixedMessage = known?.[0] === error.status ? known[1] : undefined
    const publicMessage = clientError && error.errorCode
        && PUBLIC_MESSAGE_ERROR_CODES.has(error.errorCode)
        ? error.message
        : ''

    const requestId =
        shouldShowRequestId(error)
            ? error.requestId
            : undefined

    return {
        message:
            fixedMessage
            || fieldErrorText
            || (error.status === 429 ? 'Слишком много запросов. Повторите попытку позже.' : '')
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
            ? ` Номер обращения: ${presentation.requestId}`
            : ''

    return `${
        presentation.message
    }${requestIdPart}`
}


function shouldShowRequestId(
    error: ApiError,
): boolean {
    if (!error.requestId || !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(error.requestId)) {
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
    if (!value?.trim()) {
        return undefined
    }

    const trimmed = value.trim()
    if (/^\d+(?:\.\d+)?$/.test(trimmed)) {
        return normalizeRetryAfterSeconds(Number(trimmed))
    }

    // Не интерпретировать произвольные числа или свободный текст как дату.
    if (!/^[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT$/.test(trimmed)) {
        return undefined
    }
    const timestamp = Date.parse(trimmed)

    if (!Number.isFinite(timestamp)) {
        return undefined
    }

    return normalizeRetryAfterSeconds(Math.max(0, (timestamp - Date.now()) / 1_000))
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
