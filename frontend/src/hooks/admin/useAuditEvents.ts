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
    getApiErrorMessage,
} from '../../api/http'

import {
    normalizePageResponse,
} from '../../utils/page'

const PAGE_SIZE = 50

type UseAuditEventsParams = {
    page: number
    filter: AuditEventFilter
    reloadToken: number
    onPageOutOfRange: (
        correctedPage: number,
    ) => void
}

type UseAuditEventsResult = {
    events: AuditEvent[]
    totalPages: number
    loading: boolean
    error: string
}

function useAuditEvents({
                            page,
                            filter,
                            reloadToken,
                            onPageOutOfRange,
                        }: UseAuditEventsParams): UseAuditEventsResult {
    const [events, setEvents] =
        useState<AuditEvent[]>([])

    const [totalPages, setTotalPages] =
        useState(0)

    const [loading, setLoading] =
        useState(true)

    const [error, setError] =
        useState('')

    const requestSequenceRef =
        useRef(0)

    useEffect(() => {
        const sequence =
            ++requestSequenceRef.current

        async function loadEvents(): Promise<void> {
            setLoading(true)
            setError('')

            try {
                const response =
                    await getAuditEvents(
                        page,
                        PAGE_SIZE,
                        filter,
                    )

                if (
                    sequence !==
                    requestSequenceRef.current
                ) {
                    return
                }

                const normalized =
                    normalizePageResponse(response)

                setEvents(normalized.content)
                setTotalPages(
                    normalized.totalPages,
                )

                if (
                    normalized.totalPages > 0 &&
                    page >= normalized.totalPages
                ) {
                    onPageOutOfRange(
                        normalized.totalPages - 1,
                    )
                }
            } catch (loadError) {
                if (
                    sequence !==
                    requestSequenceRef.current
                ) {
                    return
                }

                setEvents([])
                setTotalPages(0)

                setError(
                    getApiErrorMessage(
                        loadError,
                        'Не удалось загрузить аудит.',
                    ),
                )
            } finally {
                if (
                    sequence ===
                    requestSequenceRef.current
                ) {
                    setLoading(false)
                }
            }
        }

        void loadEvents()

        return () => {
            requestSequenceRef.current += 1
        }
    }, [
        page,
        filter,
        reloadToken,
        onPageOutOfRange,
    ])

    return {
        events,
        totalPages,
        loading,
        error,
    }
}

export default useAuditEvents
