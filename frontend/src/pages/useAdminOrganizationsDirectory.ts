import {
    useEffect,
    useRef,
    useState,
} from 'react'
import {
    getOrganizationDetails,
    getOrganizations,
} from '../api/organizationApi'
import type {
    Organization,
} from '../api/organizationApi'
import {
    getApiErrorMessage,
} from '../api/http'
import {
    normalizePageResponse,
} from '../utils/page'
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

    const [page, setPage] = useState(0)
    const [totalPages, setTotalPages] =
        useState(0)
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
                const response =
                    await getOrganizations(
                        page,
                        PAGE_SIZE,
                        {
                            signal:
                                controller.signal,
                        },
                    )

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
    }

    return {
        organizations,
        loadError,
        loading,
        page,
        totalPages,
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
