// ============================================================
// frontend/src/pages/adminModels/useAdminModelsOrganizationSearch.ts
// ============================================================
import { useCallback, useEffect, useRef, useState } from 'react'
import type { Dispatch, SetStateAction } from 'react'
import { searchOrganizationDirectory } from '../../api/organizationApi'
import type { OrganizationDirectoryItem } from '../../api/organizationApi'
import { getApiErrorMessage } from '../../api/http'

export function useAdminModelsOrganizationSearch(
    isSuperAdmin: boolean,
    setError: Dispatch<SetStateAction<string>>,
) {
    const [
        organizationQuery,
        setOrganizationQuery,
    ] = useState('')

    const [
        organizationResults,
        setOrganizationResults,
    ] = useState<
        OrganizationDirectoryItem[]
    >([])

    const [
        organizationSearchPending,
        setOrganizationSearchPending,
    ] = useState(false)

    const organizationSearchControllerRef = useRef<AbortController | null>(null)

    useEffect(() => () => {
        organizationSearchControllerRef.current?.abort()
    }, [])

    const cancelOrganizationSearch = useCallback(() => {
        organizationSearchControllerRef.current?.abort()
        organizationSearchControllerRef.current = null
        setOrganizationSearchPending(false)
        setOrganizationResults([])
    }, [])

    const handleOrganizationQueryChange = useCallback((value: string) => {
        cancelOrganizationSearch()
        setOrganizationQuery(value)
    }, [cancelOrganizationSearch])

    const handleOrganizationSearch =
        useCallback(async () => {
            const query =
                organizationQuery.trim()

            organizationSearchControllerRef.current?.abort()
            if (!query || !isSuperAdmin) {
                setOrganizationResults([])
                setOrganizationSearchPending(false)
                return
            }
            const controller = new AbortController()
            organizationSearchControllerRef.current = controller
            setOrganizationSearchPending(true)
            setError('')

            try {
                const results = await searchOrganizationDirectory(
                    query,
                    12,
                    { signal: controller.signal },
                )
                if (!controller.signal.aborted) {
                    setOrganizationResults(results)
                }
            } catch (failure) {
                if (!controller.signal.aborted) {
                    setError(getApiErrorMessage(
                        failure,
                        'Не удалось найти организацию.',
                    ))
                }
            } finally {
                if (organizationSearchControllerRef.current === controller) {
                    organizationSearchControllerRef.current = null
                    setOrganizationSearchPending(false)
                }
            }
        }, [organizationQuery, isSuperAdmin])

    return {
        organizationQuery,
        setOrganizationQuery,
        handleOrganizationQueryChange,
        cancelOrganizationSearch,
        organizationResults,
        setOrganizationResults,
        organizationSearchPending,
        handleOrganizationSearch,
    }
}
