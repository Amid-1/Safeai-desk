import {
    AuthCoordinationError,
    publishAuthEvent,
    runWithAuthRefreshLock,
} from './authCoordinator'

import {
    createSecureUuid,
} from '../utils/secureUuid'

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

export type ApiRequestOptions = Omit<
    RequestInit,
    'body' | 'credentials' | 'mode'
> & {
    json?: unknown
    body?: BodyInit | null
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
    timedOut: () => boolean
    externallyAborted: () => boolean
    cleanup: () => void
}

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

const MAX_ERROR_BODY_BYTES = 64 * 1024
const MAX_SUCCESS_BODY_BYTES = 4 * 1024 * 1024

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

function createRequestSignal(
    externalSignal: AbortSignal | null | undefined,
    deadline: number | null,
): RequestSignalContext {
    if (!externalSignal && deadline === null) {
        return {
            signal: undefined,
            timedOut: () => false,
            externallyAborted: () => false,
            cleanup: () => undefined,
        }
    }

    const controller = new AbortController()
    let timeoutId: number | undefined
    let didTimeout = false
    let didAbortExternally = false

    const abortFromExternal = () => {
        didAbortExternally = true
        controller.abort(externalSignal?.reason)
    }

    if (externalSignal) {
        if (externalSignal.aborted) {
            abortFromExternal()
        } else {
            externalSignal.addEventListener(
                'abort',
                abortFromExternal,
                {
                    once: true,
                },
            )
        }
    }

    if (deadline !== null) {
        const remaining = deadline - Date.now()

        if (remaining <= 0) {
            didTimeout = true
            controller.abort(
                new DOMException(
                    'Request timed out',
                    'TimeoutError',
                ),
            )
        } else {
            timeoutId = window.setTimeout(() => {
                didTimeout = true
                controller.abort(
                    new DOMException(
                        'Request timed out',
                        'TimeoutError',
                    ),
                )
            }, remaining)
        }
    }

    return {
        signal: controller.signal,
        timedOut: () => didTimeout,
        externallyAborted: () => didAbortExternally,
        cleanup: () => {
            if (timeoutId !== undefined) {
                window.clearTimeout(timeoutId)
            }

            externalSignal?.removeEventListener(
                'abort',
                abortFromExternal,
            )
        },
    }
}

function normalizeTimeout(
    timeoutMs: number | undefined,
): number | null {
    if (timeoutMs === 0) {
        return null
    }

    if (timeoutMs === undefined) {
        return API_TIMEOUTS.default
    }

    if (
        !Number.isFinite(timeoutMs)
        || timeoutMs < 0
    ) {
        throw new Error(
            'timeoutMs должен быть неотрицательным числом',
        )
    }

    return Math.max(1, Math.trunc(timeoutMs))
}

function createDeadline(
    timeoutMs: number,
): number {
    return Date.now() + timeoutMs
}

async function awaitWithDeadline<T>(
    promise: Promise<T>,
    deadline: number | null,
): Promise<T> {
    if (deadline === null) {
        return promise
    }

    const remaining = deadline - Date.now()

    if (remaining <= 0) {
        throw createTimeoutApiError()
    }

    let timeoutId: number | undefined

    try {
        return await Promise.race([
            promise,
            new Promise<never>((_resolve, reject) => {
                timeoutId = window.setTimeout(() => {
                    reject(createTimeoutApiError())
                }, remaining)
            }),
        ])
    } finally {
        if (timeoutId !== undefined) {
            window.clearTimeout(timeoutId)
        }
    }
}

async function parseErrorBody(
    response: Response,
): Promise<ApiErrorBody> {
    const responseRequestId =
        response.headers.get(
            REQUEST_ID_HEADER_NAME,
        ) ?? undefined

    const retryAfterFromHeader = parseRetryAfter(
        response.headers.get('Retry-After'),
    )

    try {
        const text = await readResponseText(
            response,
            MAX_ERROR_BODY_BYTES,
        )

        if (!text) {
            return {
                status: response.status,
                error: 'HTTP_ERROR',
                message:
                    `Запрос завершился с кодом ${response.status}`,
                requestId: responseRequestId,
                retryAfterSeconds: retryAfterFromHeader,
            }
        }

        const parsed: unknown = JSON.parse(text)

        if (!isRecord(parsed)) {
            return invalidErrorBody(
                response,
                responseRequestId,
                retryAfterFromHeader,
            )
        }

        const code = asOptionalString(parsed.code)
        const error = asOptionalString(parsed.error)
        const retryAfterFromBody =
            asOptionalNonNegativeNumber(
                parsed.retryAfterSeconds,
            )

        return {
            timestamp: asOptionalString(
                parsed.timestamp,
            ),
            status:
                asOptionalNumber(parsed.status)
                ?? response.status,
            code,
            error: error ?? code,
            message: asOptionalString(
                parsed.message,
            ),
            path: asOptionalString(parsed.path),
            requestId:
                asOptionalString(parsed.requestId)
                ?? responseRequestId,
            fieldErrors: parseFieldErrors(
                parsed.fieldErrors,
            ),
            retryAfterSeconds:
                retryAfterFromHeader
                ?? retryAfterFromBody,
        }
    } catch {
        return invalidErrorBody(
            response,
            responseRequestId,
            retryAfterFromHeader,
        )
    }
}

async function parseSuccessBody<T>(
    response: Response,
): Promise<T> {
    if (
        response.status === 204
        || response.status === 205
    ) {
        return undefined as T
    }

    const text = await readResponseText(
        response,
        MAX_SUCCESS_BODY_BYTES,
    )

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
                message:
                    'Сервер вернул некорректный JSON-ответ',
                requestId:
                    response.headers.get(
                        REQUEST_ID_HEADER_NAME,
                    ) ?? undefined,
            },
            response.status,
        )
    }
}

async function readResponseText(
    response: Response,
    maxBytes: number,
): Promise<string> {
    const contentLength = Number(
        response.headers.get('Content-Length'),
    )

    if (
        Number.isFinite(contentLength)
        && contentLength > maxBytes
    ) {
        throw new ApiError(
            'Ответ сервера превышает допустимый размер',
            {
                status: response.status,
                error: 'RESPONSE_TOO_LARGE',
                message:
                    'Ответ сервера превышает допустимый размер',
            },
            response.status,
        )
    }

    const text = await response.text()
    const byteLength = new TextEncoder()
        .encode(text)
        .byteLength

    if (byteLength > maxBytes) {
        throw new ApiError(
            'Ответ сервера превышает допустимый размер',
            {
                status: response.status,
                error: 'RESPONSE_TOO_LARGE',
                message:
                    'Ответ сервера превышает допустимый размер',
            },
            response.status,
        )
    }

    return text
}

async function createApiErrorFromResponse(
    response: Response,
    fallbackMessage: string,
): Promise<ApiError> {
    const body = await parseErrorBody(response)

    return new ApiError(
        body.message || fallbackMessage,
        body,
        response.status,
    )
}

function invalidErrorBody(
    response: Response,
    requestId: string | undefined,
    retryAfterSeconds: number | undefined,
): ApiErrorBody {
    return {
        status: response.status,
        error: 'INVALID_ERROR_RESPONSE',
        message:
            `Сервер вернул некорректный ответ с кодом ${response.status}`,
        requestId,
        retryAfterSeconds,
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
        || requestPath === '/api/auth/me'
}

function createRequestId(): string {
    return createSecureUuid()
}

function createAbortedApiError(
    requestId?: string,
): ApiError {
    return new ApiError(
        'Запрос был отменён',
        {
            status: 0,
            error: 'REQUEST_ABORTED',
            message: 'Запрос был отменён',
            requestId,
        },
        0,
    )
}

function createTimeoutApiError(
    requestId?: string,
): ApiError {
    return new ApiError(
        'Превышено время ожидания ответа сервера',
        {
            status: 0,
            error: 'REQUEST_TIMEOUT',
            message:
                'Превышено время ожидания ответа сервера',
            requestId,
        },
        0,
    )
}

function toNetworkApiError(
    error: unknown,
    fallbackMessage: string,
    requestId?: string,
): ApiError {
    if (error instanceof ApiError) {
        return error
    }

    if (
        error instanceof DOMException
        && error.name === 'AbortError'
    ) {
        return new ApiError(
            'Запрос был отменён',
            {
                status: 0,
                error: 'REQUEST_ABORTED',
                message: 'Запрос был отменён',
                requestId,
            },
            0,
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
        0,
    )
}

function toCoordinationApiError(
    error: unknown,
): ApiError {
    if (error instanceof ApiError) {
        return error
    }

    if (error instanceof AuthCoordinationError) {
        return new ApiError(
            error.message,
            {
                status: 0,
                error: error.code,
                message: error.message,
            },
            0,
        )
    }

    return toNetworkApiError(
        error,
        'Не удалось согласовать обновление сессии между вкладками',
    )
}

function isRecord(
    value: unknown,
): value is Record<string, unknown> {
    return typeof value === 'object'
        && value !== null
        && !Array.isArray(value)
}

function asOptionalString(
    value: unknown,
): string | undefined {
    return typeof value === 'string'
        ? value
        : undefined
}

function asOptionalNumber(
    value: unknown,
): number | undefined {
    return (
        typeof value === 'number'
        && Number.isFinite(value)
    )
        ? value
        : undefined
}

function asOptionalNonNegativeNumber(
    value: unknown,
): number | undefined {
    const parsed = asOptionalNumber(value)

    return parsed !== undefined && parsed >= 0
        ? parsed
        : undefined
}

function parseFieldErrors(
    value: unknown,
): Record<string, string[]> | null | undefined {
    if (value === null) {
        return null
    }

    if (!isRecord(value)) {
        return undefined
    }

    const result: Record<string, string[]> = {}

    Object.entries(value).forEach(
        ([field, messages]) => {
            if (!Array.isArray(messages)) {
                return
            }

            const normalizedMessages =
                messages.filter(
                    (
                        message,
                    ): message is string =>
                        typeof message === 'string',
                )

            if (normalizedMessages.length > 0) {
                result[field] =
                    normalizedMessages
            }
        },
    )

    return result
}
