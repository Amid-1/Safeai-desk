import { ApiError, parseRetryAfter } from './httpErrors'
import type { ApiErrorBody } from './httpErrors'

const REQUEST_ID_HEADER_NAME = 'X-Request-Id'



const MAX_ERROR_BODY_BYTES = 64 * 1024

const MAX_SUCCESS_BODY_BYTES = 4 * 1024 * 1024

const MAX_BINARY_RESPONSE_BYTES =
    100 * 1024 * 1024


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


export async function parseSuccessBlob(
    response: Response,
): Promise<Blob> {
    if (
        response.status === 204
        || response.status === 205
    ) {
        return new Blob()
    }

    const contentLength = Number(
        response.headers.get(
            'Content-Length',
        ),
    )

    if (
        Number.isFinite(contentLength)
        && contentLength
            > MAX_BINARY_RESPONSE_BYTES
    ) {
        throw new ApiError(
            'Файл превышает допустимый размер ответа',
            {
                status: response.status,
                error: 'RESPONSE_TOO_LARGE',
                message:
                    'Файл превышает допустимый размер ответа',
                requestId:
                    response.headers.get(
                        REQUEST_ID_HEADER_NAME,
                    ) ?? undefined,
            },
            response.status,
        )
    }

    const blob = await response.blob()

    if (
        blob.size
        > MAX_BINARY_RESPONSE_BYTES
    ) {
        throw new ApiError(
            'Файл превышает допустимый размер ответа',
            {
                status: response.status,
                error: 'RESPONSE_TOO_LARGE',
                message:
                    'Файл превышает допустимый размер ответа',
                requestId:
                    response.headers.get(
                        REQUEST_ID_HEADER_NAME,
                    ) ?? undefined,
            },
            response.status,
        )
    }

    return blob
}


export async function parseSuccessBody<T>(
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


export async function createApiErrorFromResponse(
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
