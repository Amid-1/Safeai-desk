import { AuthCoordinationError } from './authCoordinator'
import { ApiError } from './httpErrors'



type RequestSignalContext = {
    signal?: AbortSignal
    timedOut: () => boolean
    externallyAborted: () => boolean
    cleanup: () => void
}


export function createRequestSignal(
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


export function normalizeTimeout(
    timeoutMs: number | undefined,
): number | null {
    if (timeoutMs === 0) {
        return null
    }

    if (timeoutMs === undefined) {
        return 30_000
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


export function createDeadline(
    timeoutMs: number,
): number {
    return Date.now() + timeoutMs
}


export async function awaitWithDeadline<T>(
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


export function createAbortedApiError(
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


export function createTimeoutApiError(
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


export function toNetworkApiError(
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


export function toCoordinationApiError(
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
