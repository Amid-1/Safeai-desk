// frontend/src/features/models/controller/useAdminModelsOrganizationSearch.ts
// ============================================================
// frontend/src/features/models/controller/useAdminModelsOrganizationSearch.ts
// Поиск организаций: debounced GET, явные состояния, отмена и защита от гонок.
// ============================================================
import { useCallback, useEffect, useRef, useState } from 'react'
import { searchOrganizationDirectory } from '../../../entities/organization/api/organizationApi'
import type { OrganizationDirectoryItem } from '../../../entities/organization/api/organizationApi'
import { getApiErrorMessage } from '../../../shared/api/http'

export type OrganizationSearchStatus = 'idle' | 'pending' | 'success' | 'empty' | 'error'

const SEARCH_DEBOUNCE_MS = 300
const SEARCH_LIMIT = 12

export function useAdminModelsOrganizationSearch(isSuperAdmin: boolean) {
    const [organizationQuery, setOrganizationQuery] = useState('')
    const [organizationResults, setOrganizationResults] = useState<OrganizationDirectoryItem[]>([])
    const [organizationSearchStatus, setOrganizationSearchStatus] = useState<OrganizationSearchStatus>('idle')
    const [organizationSearchError, setOrganizationSearchError] = useState('')
    const organizationSearchControllerRef = useRef<AbortController | null>(null)
    // Номер поколения необходим даже для API/mocks, не выполняющих AbortSignal.
    const generationRef = useRef(0)
    const queryRef = useRef('')
    const lastSearchedQueryRef = useRef('')

    const cancelOrganizationSearch = useCallback(() => {
        generationRef.current += 1
        organizationSearchControllerRef.current?.abort()
        organizationSearchControllerRef.current = null
        setOrganizationResults([])
        setOrganizationSearchError('')
        setOrganizationSearchStatus('idle')
    }, [])

    const handleOrganizationQueryChange = useCallback((value: string) => {
        queryRef.current = value
        lastSearchedQueryRef.current = ''
        cancelOrganizationSearch()
        setOrganizationQuery(value)
    }, [cancelOrganizationSearch])

    const handleOrganizationSearch = useCallback(async () => {
        const query = queryRef.current.trim()
        lastSearchedQueryRef.current = query
        generationRef.current += 1
        const generation = generationRef.current
        organizationSearchControllerRef.current?.abort()
        organizationSearchControllerRef.current = null
        setOrganizationResults([])
        setOrganizationSearchError('')

        if (!isSuperAdmin || query.length < 2) {
            setOrganizationSearchStatus('idle')
            return
        }

        const controller = new AbortController()
        organizationSearchControllerRef.current = controller
        setOrganizationSearchStatus('pending')

        try {
            const results = await searchOrganizationDirectory(query, SEARCH_LIMIT, {
                signal: controller.signal,
            })
            if (generationRef.current !== generation || controller.signal.aborted) return
            setOrganizationResults(results)
            setOrganizationSearchStatus(results.length ? 'success' : 'empty')
        } catch (failure) {
            if (generationRef.current !== generation || controller.signal.aborted) return
            setOrganizationSearchError(getApiErrorMessage(
                failure,
                'Не удалось выполнить поиск организаций. Повторите попытку.',
            ))
            setOrganizationSearchStatus('error')
        } finally {
            if (generationRef.current === generation) {
                organizationSearchControllerRef.current = null
            }
        }
    }, [isSuperAdmin])

    // Подсказки при вводе без лишних запросов и без «пустых» результатов.
    useEffect(() => {
        if (!isSuperAdmin || organizationQuery.trim().length < 2
            || lastSearchedQueryRef.current === organizationQuery.trim()) return
        const timeoutId = window.setTimeout(() => {
            // Явный поиск мог завершиться до исполнения старого debounce-таймера.
            if (lastSearchedQueryRef.current !== organizationQuery.trim()) {
                void handleOrganizationSearch()
            }
        }, SEARCH_DEBOUNCE_MS)
        return () => window.clearTimeout(timeoutId)
    }, [handleOrganizationSearch, isSuperAdmin, organizationQuery])

    // При потере права SUPER_ADMIN прекращаем поиск и скрываем tenant-данные.
    useEffect(() => {
        if (!isSuperAdmin) cancelOrganizationSearch()
    }, [isSuperAdmin, cancelOrganizationSearch])

    useEffect(() => () => {
        generationRef.current += 1
        organizationSearchControllerRef.current?.abort()
    }, [])

    return {
        organizationQuery,
        setOrganizationQuery,
        handleOrganizationQueryChange,
        cancelOrganizationSearch,
        organizationResults,
        setOrganizationResults,
        organizationSearchPending: organizationSearchStatus === 'pending',
        organizationSearchStatus,
        organizationSearchError,
        handleOrganizationSearch,
    }
}
