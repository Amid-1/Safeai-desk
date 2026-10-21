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
        clearSearchTimer(organizationSearchTimerRef)
        clearSearchTimer(actorSearchTimerRef)
    }, [])

    const scheduleOrganizationSearch = useCallback((query: string) => {
        setOrganizationQuery(query)
        clearSearchTimer(organizationSearchTimerRef)
        organizationSearchTimerRef.current = window.setTimeout(() => {
            organizationSearchTimerRef.current = null
            void onOrganizationSearchRef.current(query)
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
        actorQuery,
        scheduleOrganizationSearch,
        scheduleActorSearch,
    }
}
