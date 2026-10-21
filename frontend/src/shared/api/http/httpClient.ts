// ============================================================
// frontend/src/shared/api/http/httpClient.ts
// ============================================================

import { ApiError } from '../httpErrors'
import { parseSuccessBlob, parseSuccessBody, createApiErrorFromResponse } from '../httpResponseSupport'
import { normalizeTimeout } from '../httpRequestSupport'
import { CSRF_HEADER_NAME, REQUEST_ID_HEADER_NAME, CSRF_RETRYABLE_ERROR_CODES } from './httpConfig'
import { isUnsafeMethod, isAuthEndpoint } from './httpPaths'
import { fetchWithApiError, createRequestId } from './httpTransport'
import { requireCsrfToken, fetchCsrfToken, refreshAccessToken, notifyUnauthorized } from './httpAuthFlow'

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

type ApiRequestContext = {
    deadline: number | null
    requestId: string
    allowRefresh: boolean
    allowCsrfRetry: boolean
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
