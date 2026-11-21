// frontend/src/features/audit/ui/auditFilters/useAuditDirectoryQueries.ts
// ============================================================
// frontend/src/features/audit/ui/auditFilters/useAuditDirectoryQueries.ts
// ============================================================
import { useCallback, useEffect, useRef, useState } from 'react'
import type { AuditFiltersProps } from './auditFilterTypes'

const DIRECTORY_SEARCH_DELAY_MS = 300

type SearchTimerRef = { current: number | null }

function clearSearchTimer(timerRef: SearchTimerRef) {
    if (timerRef.current === null) return
    window.clearTimeout(timerRef.current)
    timerRef.current = null
}

export function useAuditDirectoryQueries(
    onActorSearch: AuditFiltersProps['onActorSearch'],
    onOrganizationSearch: AuditFiltersProps['onOrganizationSearch'],
) {
    const [organizationQuery, setOrganizationQuery] = useState('')
    const [organizationSearchScheduled, setOrganizationSearchScheduled] = useState(false)
    const organizationSequence = useRef(0)
    const [actorQuery, setActorQuery] = useState('')
    const organizationSearchTimerRef = useRef<number | null>(null)
    const actorSearchTimerRef = useRef<number | null>(null)

    // Колбэки актуализируются без пересоздания ожидающих таймеров.
    const onActorSearchRef = useRef(onActorSearch)
    const onOrganizationSearchRef = useRef(onOrganizationSearch)
    useEffect(() => {
        onActorSearchRef.current = onActorSearch
        onOrganizationSearchRef.current = onOrganizationSearch
    }, [onActorSearch, onOrganizationSearch])

    useEffect(() => () => {
        organizationSequence.current += 1
        clearSearchTimer(organizationSearchTimerRef)
        clearSearchTimer(actorSearchTimerRef)
    }, [])

    const scheduleOrganizationSearch = useCallback((query: string) => {
        setOrganizationQuery(query)
        setOrganizationSearchScheduled(true)
        const sequence = ++organizationSequence.current
        clearSearchTimer(organizationSearchTimerRef)
        organizationSearchTimerRef.current = window.setTimeout(() => {
            organizationSearchTimerRef.current = null
            void onOrganizationSearchRef.current(query).finally(() => {
                if (organizationSequence.current === sequence) setOrganizationSearchScheduled(false)
            })
        }, DIRECTORY_SEARCH_DELAY_MS)
    }, [])

    const scheduleActorSearch = useCallback((query: string) => {
        setActorQuery(query)
        clearSearchTimer(actorSearchTimerRef)
        // Инициатор и целевая организация — независимые измерения аудита.
        // Справочный поиск инициатора не ограничивается targetOrganizationId.
        actorSearchTimerRef.current = window.setTimeout(() => {
            actorSearchTimerRef.current = null
            void onActorSearchRef.current(query)
        }, DIRECTORY_SEARCH_DELAY_MS)
    }, [])

    return {
        organizationQuery,
        organizationSearchScheduled,
        actorQuery,
        scheduleOrganizationSearch,
        scheduleActorSearch,
    }
}
