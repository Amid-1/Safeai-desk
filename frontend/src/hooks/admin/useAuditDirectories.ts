// ============================================================
// frontend/src/hooks/admin/useAuditDirectories.ts
// ============================================================
import {
    useCallback,
    useEffect,
    useRef,
    useState,
} from 'react'
import {
    getAuditEventTypes,
    searchAuditActors,
    searchAuditTargetOrganizations,
} from '../../api/adminApi'
import type {
    AuditActorDirectoryItem,
    AuditTargetOrganizationDirectoryItem,
} from '../../api/adminApi'
import {
    ApiError,
    getApiErrorMessage,
} from '../../api/http'
import {
    AUDIT_EVENT_TYPES,
} from '../../constants/auditEvents'

const DIRECTORY_LIMIT = 20

type UseAuditDirectoriesResult = {
    eventTypes: string[]
    organizations:
        AuditTargetOrganizationDirectoryItem[]
    actors: AuditActorDirectoryItem[]

    loading: boolean

    eventTypesError: string
    organizationsError: string
    actorsError: string

    searchOrganizations:
        (query: string) => Promise<void>

    searchActors: (
        query: string,
        targetOrganizationId?: string,
    ) => Promise<void>
}

function useAuditDirectories(
    superAdmin: boolean,
): UseAuditDirectoriesResult {
    const [
        eventTypes,
        setEventTypes,
    ] = useState<string[]>([
        ...AUDIT_EVENT_TYPES,
    ])

    const [
        organizations,
        setOrganizations,
    ] = useState<
        AuditTargetOrganizationDirectoryItem[]
    >([])

    const [actors, setActors] =
        useState<AuditActorDirectoryItem[]>(
            [],
        )

    const [loading, setLoading] =
        useState(true)

    const [
        eventTypesError,
        setEventTypesError,
    ] = useState('')

    const [
        organizationsError,
        setOrganizationsError,
    ] = useState('')

    const [
        actorsError,
        setActorsError,
    ] = useState('')

    const eventTypesControllerRef =
        useRef<AbortController | null>(
            null,
        )

    const organizationControllerRef =
        useRef<AbortController | null>(
            null,
        )

    const actorControllerRef =
        useRef<AbortController | null>(
            null,
        )

    const organizationSequenceRef =
        useRef(0)

    const actorSequenceRef =
        useRef(0)

    const searchOrganizations =
        useCallback(
            async (query: string) => {
                if (!superAdmin) {
                    setOrganizations([])
                    setOrganizationsError('')
                    return
                }

                const sequence =
                    ++organizationSequenceRef.current

                organizationControllerRef.current
                    ?.abort()

                const controller =
                    new AbortController()

                organizationControllerRef.current =
                    controller

                setOrganizationsError('')

                try {
                    const result =
                        await searchAuditTargetOrganizations(
                            query,
                            DIRECTORY_LIMIT,
                            {
                                signal:
                                    controller.signal,
                            },
                        )

                    if (
                        sequence
                        === organizationSequenceRef.current
                    ) {
                        setOrganizations(
                            result,
                        )
                    }
                } catch (error) {
                    if (
                        sequence
                        === organizationSequenceRef.current
                        && !isRequestAborted(
                            error,
                        )
                    ) {
                        setOrganizations([])
                        setOrganizationsError(
                            getApiErrorMessage(
                                error,
                                'Не удалось загрузить исторический каталог организаций аудита.',
                            ),
                        )
                    }
                }
            },
            [superAdmin],
        )

    const searchActors =
        useCallback(
            async (
                query: string,
                targetOrganizationId?: string,
            ) => {
                const sequence =
                    ++actorSequenceRef.current

                actorControllerRef.current
                    ?.abort()

                const controller =
                    new AbortController()

                actorControllerRef.current =
                    controller

                setActorsError('')

                try {
                    const result =
                        await searchAuditActors(
                            query,
                            targetOrganizationId,
                            DIRECTORY_LIMIT,
                            {
                                signal:
                                    controller.signal,
                            },
                        )

                    if (
                        sequence
                        === actorSequenceRef.current
                    ) {
                        setActors(result)
                    }
                } catch (error) {
                    if (
                        sequence
                        === actorSequenceRef.current
                        && !isRequestAborted(
                            error,
                        )
                    ) {
                        setActors([])
                        setActorsError(
                            getApiErrorMessage(
                                error,
                                'Не удалось загрузить исторический каталог акторов.',
                            ),
                        )
                    }
                }
            },
            [],
        )

    useEffect(() => {
        eventTypesControllerRef.current
            ?.abort()

        const controller =
            new AbortController()

        eventTypesControllerRef.current =
            controller

        let active = true

        async function loadInitial() {
            setLoading(true)
            setEventTypesError('')

            const results =
                await Promise.allSettled([
                    getAuditEventTypes({
                        signal:
                            controller.signal,
                    }),
                    searchOrganizations(''),
                    searchActors(''),
                ])

            if (
                !active
                || controller.signal.aborted
            ) {
                return
            }

            const eventTypeResult =
                results[0]

            if (
                eventTypeResult.status
                    === 'fulfilled'
            ) {
                setEventTypes(
                    eventTypeResult.value,
                )
            } else if (
                !isRequestAborted(
                    eventTypeResult.reason,
                )
            ) {
                // Известный static список остаётся fallback,
                // но ошибка не скрывается.
                setEventTypes([
                    ...AUDIT_EVENT_TYPES,
                ])
                setEventTypesError(
                    getApiErrorMessage(
                        eventTypeResult.reason,
                        'Не удалось загрузить справочник типов событий. Используется ограниченный fallback-список.',
                    ),
                )
            }

            setLoading(false)
        }

        void loadInitial()

        return () => {
            active = false
            controller.abort()

            eventTypesControllerRef.current
                ?.abort()
            organizationControllerRef.current
                ?.abort()
            actorControllerRef.current
                ?.abort()

            organizationSequenceRef.current
                += 1
            actorSequenceRef.current += 1
        }
    }, [
        searchOrganizations,
        searchActors,
    ])

    return {
        eventTypes,
        organizations,
        actors,

        loading,

        eventTypesError,
        organizationsError,
        actorsError,

        searchOrganizations,
        searchActors,
    }
}

function isRequestAborted(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && error.errorCode
            === 'REQUEST_ABORTED'
}

export default useAuditDirectories
