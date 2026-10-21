// ============================================================
// frontend/src/shared/api/http/httpTransport.ts
// ============================================================

import { buildApiUrl } from './httpPaths'
import { REQUEST_ID_HEADER_NAME } from './httpConfig'
import { createRequestSignal, createTimeoutApiError, createAbortedApiError, toNetworkApiError } from '../httpRequestSupport'
import { createSecureUuid } from '../../lib/secureUuid'

export async function fetchWithApiError(
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

export function createRequestId(): string {
    return createSecureUuid()
}
