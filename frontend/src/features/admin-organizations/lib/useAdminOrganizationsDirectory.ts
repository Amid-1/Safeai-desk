// frontend/src/features/admin-organizations/lib/useAdminOrganizationsDirectory.ts
import {
    useEffect,
    useCallback,
    useRef,
    useState,
} from 'react'
import {
    getOrganizationDetails,
    getOrganizations,
} from '../../../entities/organization/api/organizationApi'
import type {
    Organization,
} from '../../../entities/organization/api/organizationApi'
import {
    getApiErrorMessage,
} from '../../../shared/api/http'
import {
    normalizePageResponse,
} from '../../../shared/lib/page'
import { directoryPage, directoryUuid, useDirectoryUrl } from '../../../shared/lib/adminDirectoryUrl'
import type { OrganizationType } from '../../../entities/organization/api/organizationApi'
import {
    isOrganizationRequestAborted,
} from './adminOrganizationsSupport'

const PAGE_SIZE = 50

export function useAdminOrganizationsDirectory() {
    const [organizations, setOrganizations] =
        useState<Organization[]>([])
    const [loadError, setLoadError] =
        useState('')
    const [loading, setLoading] =
        useState(true)

    const {params, change} = useDirectoryUrl()
    const page = directoryPage(params.get('page'))
    const setPage = useCallback((next: number) => change({page: next ? String(next) : ''}), [change])
    const query = (params.get('query') ?? '').slice(0, 255)
    const rawType = params.get('type')
    const type: OrganizationType | '' = rawType === 'PLATFORM' || rawType === 'TENANT' ? rawType : ''
    const rawStatus = params.get('status')
    const status = rawStatus === 'ACTIVE' || rawStatus === 'DISABLED' ? rawStatus : ''
    const linkedId = directoryUuid(params.get('organizationId'))
    const [totalPages, setTotalPages] =
        useState(0)
    const [totalOrganizations, setTotalOrganizations] = useState<number | null>(null)
    const [totalMatched, setTotalMatched] = useState<number | null>(null)
    const [reloadToken, setReloadToken] =
        useState(0)

    const [detailsOrganization, setDetailsOrganization] =
        useState<Organization | null>(null)
    const [detailsLoadingId, setDetailsLoadingId] =
        useState<string | null>(null)
    const [detailsError, setDetailsError] =
        useState('')

    const loadSequenceRef = useRef(0)
    const loadControllerRef =
        useRef<AbortController | null>(null)
    const detailsSequenceRef = useRef(0)
    const detailsControllerRef =
        useRef<AbortController | null>(null)

    useEffect(() => {
        const sequence =
            ++loadSequenceRef.current

        loadControllerRef.current?.abort()

        const controller =
            new AbortController()

        loadControllerRef.current = controller

        async function loadOrganizations() {
            setLoading(true)
            setLoadError('')

            try {
                const [response, global] = await Promise.all([
                    getOrganizations(
                        page,
                        PAGE_SIZE,
                        {
                            signal:
                                controller.signal,
                            query: query || undefined,
                            type: type || undefined,
                            enabled: status ? status === 'ACTIVE' : undefined,
                        },
                    )
                    , query || type || status
                        ? getOrganizations(0, 1, {signal: controller.signal}) : Promise.resolve(null)
                ])

                if (
                    sequence
                    !== loadSequenceRef.current
                ) {
                    return
                }

                const normalized =
                    normalizePageResponse(response)

                if (
                    normalized.totalPages === 0
                    && page !== 0
                ) {
                    setPage(0)
                    return
                }

                if (
                    normalized.totalPages > 0
                    && page >= normalized.totalPages
                ) {
                    setPage(
                        normalized.totalPages - 1,
                    )
                    return
                }

                setOrganizations(
                    normalized.content,
                )
                setTotalPages(
                    normalized.totalPages,
                )
                setTotalMatched(normalized.totalElements)
                setTotalOrganizations(global ? global.totalElements : normalized.totalElements)
            } catch (error) {
                if (
                    sequence
                    === loadSequenceRef.current
                    && !isOrganizationRequestAborted(
                        error,
                    )
                ) {
                    setOrganizations([])
                    setTotalPages(0)
                    setTotalOrganizations(null)
                    setTotalMatched(null)
                    setLoadError(
                        getApiErrorMessage(
                            error,
                            'Не удалось загрузить организации.',
                        ),
                    )
                }
            } finally {
                if (
                    sequence
                    === loadSequenceRef.current
                ) {
                    setLoading(false)
                }
            }
        }

        void loadOrganizations()

        return () => {
            controller.abort()
            loadSequenceRef.current += 1
        }
    }, [
        page,
        reloadToken,
        query,
        type,
        status,
        setPage,
    ])

    useEffect(() => {
        return () => {
            loadControllerRef.current?.abort()
            detailsControllerRef.current?.abort()
        }
    }, [])

    function requestReloadFromFirstPage() {
        if (page === 0) {
            setReloadToken(
                (value) => value + 1,
            )
        } else {
            setPage(0)
        }
    }

    function reloadCurrentPage() {
        setReloadToken(
            (value) => value + 1,
        )
    }

    async function openDetailsModal(
        organization: Organization,
    ) {
        const sequence =
            ++detailsSequenceRef.current

        detailsControllerRef.current?.abort()

        const controller =
            new AbortController()

        detailsControllerRef.current = controller

        setDetailsLoadingId(
            organization.id,
        )
        setDetailsError('')

        try {
            const details =
                await getOrganizationDetails(
                    organization.id,
                    {
                        signal:
                            controller.signal,
                    },
                )

            if (
                sequence
                !== detailsSequenceRef.current
            ) {
                return
            }

            setDetailsOrganization(details)
        } catch (error) {
            if (
                sequence
                === detailsSequenceRef.current
                && !isOrganizationRequestAborted(
                    error,
                )
            ) {
                setDetailsError(
                    getApiErrorMessage(
                        error,
                        'Не удалось загрузить сведения об организации.',
                    ),
                )

                // Fail-soft presentation only: the already loaded page
                // snapshot remains visible, while the warning is preserved.
                setDetailsOrganization(
                    organization,
                )
            }
        } finally {
            if (
                sequence
                === detailsSequenceRef.current
            ) {
                setDetailsLoadingId(null)
            }
        }
    }

    function closeDetailsModal() {
        detailsSequenceRef.current += 1
        detailsControllerRef.current?.abort()
        setDetailsOrganization(null)
        setDetailsLoadingId(null)
        setDetailsError('')
        if (linkedId) change({organizationId: ''}, true)
    }

    useEffect(() => {
        if (!linkedId) return
        const controller = new AbortController()
        setDetailsLoadingId(linkedId)
        void getOrganizationDetails(linkedId, {signal: controller.signal})
            .then(row => {if (!controller.signal.aborted) setDetailsOrganization(row)})
            .catch(() => {if (!controller.signal.aborted) setLoadError('Не удалось открыть выбранную организацию.')})
            .finally(() => {if (!controller.signal.aborted) setDetailsLoadingId(null)})
        return () => controller.abort()
    }, [linkedId])

    return {
        organizations,
        loadError,
        loading,
        page,
        totalPages,
        totalOrganizations,
        totalMatched,
        query, type, status,
        changeFilters: (nextQuery: string, nextType: string, nextStatus: string, replace = false) =>
            change({query: nextQuery.trim(), type: nextType, status: nextStatus, page: ''}, replace),
        setPage,
        requestReloadFromFirstPage,
        reloadCurrentPage,
        detailsOrganization,
        detailsLoadingId,
        detailsError,
        openDetailsModal,
        closeDetailsModal,
    }
}
