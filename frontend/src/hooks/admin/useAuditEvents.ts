// ============================================================
// frontend/src/hooks/admin/useAuditEvents.ts
// ============================================================
import {
    useEffect,
    useRef,
    useState,
} from 'react'
import {
    getAuditEvents,
} from '../../api/adminApi'
import type {
    AuditEvent,
    AuditEventFilter,
} from '../../api/adminApi'
import {
    ApiError,
    getApiErrorMessage,
} from '../../api/http'
import type {
    PageResponse,
} from '../../utils/page'

const PAGE_SIZE = 50

type UseAuditEventsParams = {
    page: number
    filter: AuditEventFilter
    reloadToken: number
    onPageOutOfRange:
        (correctedPage: number) => void
}

type UseAuditEventsResult = {
    events: AuditEvent[]
    pageResponse:
        PageResponse<AuditEvent>
    loading: boolean
    error: string
}

const EMPTY_PAGE:
    PageResponse<AuditEvent> = {
    content: [],
    page: 0,
    size: PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
}

function useAuditEvents({
    page,
    filter,
    reloadToken,
    onPageOutOfRange,
}: UseAuditEventsParams): UseAuditEventsResult {
    const [pageResponse, setPageResponse] =
        useState<PageResponse<AuditEvent>>(
            EMPTY_PAGE,
        )

    const [loading, setLoading] =
        useState(true)

    const [error, setError] =
        useState('')

    const requestSequenceRef =
        useRef(0)

    const callbackRef =
        useRef(onPageOutOfRange)

    callbackRef.current =
        onPageOutOfRange

    useEffect(() => {
        const sequence =
            ++requestSequenceRef.current

        const controller =
            new AbortController()

        async function loadEvents() {
            setLoading(true)
            setError('')

            try {
                const response =
                    await getAuditEvents(
                        page,
                        PAGE_SIZE,
                        filter,
                        {
                            signal:
                                controller.signal,
                        },
                    )

                if (
                    sequence
                    !== requestSequenceRef.current
                ) {
                    return
                }

                if (
                    response.totalPages === 0
                    && page !== 0
                ) {
                    callbackRef.current(0)
                    return
                }

                if (
                    response.totalPages > 0
                    && page
                        >= response.totalPages
                ) {
                    callbackRef.current(
                        response.totalPages - 1,
                    )
                    return
                }

                setPageResponse(response)
            } catch (loadError) {
                if (
                    sequence
                    === requestSequenceRef.current
                    && !isRequestAborted(
                        loadError,
                    )
                ) {
                    setPageResponse(
                        EMPTY_PAGE,
                    )
                    setError(
                        getApiErrorMessage(
                            loadError,
                            'Не удалось загрузить аудит.',
                        ),
                    )
                }
            } finally {
                if (
                    sequence
                    === requestSequenceRef.current
                ) {
                    setLoading(false)
                }
            }
        }

        void loadEvents()

        return () => {
            controller.abort()
            requestSequenceRef.current += 1
        }
    }, [
        page,
        filter.eventType,
        filter.actorUserId,
        filter.actorEmail,
        filter.dateFrom,
        filter.dateTo,
        filter.targetOrganizationId,
        reloadToken,
    ])

    return {
        events: pageResponse.content,
        pageResponse,
        loading,
        error,
    }
}

function isRequestAborted(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && error.errorCode
            === 'REQUEST_ABORTED'
}

export default useAuditEvents
