// frontend/src/features/usage/controller/useAdminUsageOrganizationOptions.ts

import {
    useEffect,
    useMemo,
    useState,
} from 'react'
import {
    getOrganizationDetails,
    searchOrganizationDirectory,
} from '../../../entities/organization/api/organizationApi'
import type {
    OrganizationDirectoryItem,
} from '../../../entities/organization/api/organizationApi'
import {
    getApiErrorMessage,
} from '../../../shared/api/http'
import type {
    UsageFilterOption,
} from '../ui/UsageFilterCombobox'
import {
    isAbortError,
    isUsageOrganizationId,
} from '../lib/adminUsage.helpers'

const SEARCH_LIMIT = 20
const SEARCH_DEBOUNCE_MS = 220

const ALL_ORGANIZATIONS_OPTION: UsageFilterOption = {
    value: '',
    label: 'Все организации',
    description: 'Глобальный отчёт по всем организациям',
}

type UseAdminUsageOrganizationOptionsInput = {
    enabled: boolean
    draftOrganizationId: string
    appliedOrganizationId: string
}

export function useAdminUsageOrganizationOptions({
    enabled,
    draftOrganizationId,
    appliedOrganizationId,
}: UseAdminUsageOrganizationOptionsInput) {
    const [query, setQuery] =
        useState('')
    const [results, setResults] =
        useState<OrganizationDirectoryItem[]>([])
    const [names, setNames] =
        useState<Record<string, string>>({})
    const [loading, setLoading] =
        useState(false)
    const [error, setError] =
        useState('')

    useEffect(() => {
        if (!enabled) {
            setResults([])
            setLoading(false)
            setError('')
            return
        }

        const controller = new AbortController()
        const timer = window.setTimeout(
            () => {
                async function search() {
                    setLoading(true)
                    setError('')

                    try {
                        const organizations =
                            await searchOrganizationDirectory(
                                query,
                                SEARCH_LIMIT,
                                {
                                    signal:
                                        controller.signal,
                                },
                            )

                        if (controller.signal.aborted) {
                            return
                        }

                        setResults(organizations)
                        setNames((current) =>
                            mergeNames(
                                current,
                                organizations,
                            ),
                        )
                    } catch (searchError) {
                        if (
                            controller.signal.aborted
                            || isAbortError(searchError)
                        ) {
                            return
                        }

                        setResults([])
                        setError(
                            getApiErrorMessage(
                                searchError,
                                'Не удалось найти организации.',
                            ),
                        )
                    } finally {
                        if (!controller.signal.aborted) {
                            setLoading(false)
                        }
                    }
                }

                void search()
            },
            SEARCH_DEBOUNCE_MS,
        )

        return () => {
            window.clearTimeout(timer)
            controller.abort()
        }
    }, [
        enabled,
        query,
    ])

    useEffect(() => {
        if (!enabled) {
            return
        }

        const ids = Array.from(
            new Set([
                draftOrganizationId.trim(),
                appliedOrganizationId.trim(),
            ]),
        ).filter(
            (id) =>
                id
                && isUsageOrganizationId(id)
                && !names[id],
        )

        if (ids.length === 0) {
            return
        }

        const controller = new AbortController()

        async function resolveNames() {
            const settled = await Promise.allSettled(
                ids.map(async (id) => {
                    const organization =
                        await getOrganizationDetails(
                            id,
                            {
                                signal:
                                    controller.signal,
                            },
                        )

                    return {
                        id: organization.id,
                        name: organization.name,
                    }
                }),
            )

            if (controller.signal.aborted) {
                return
            }

            setNames((current) => {
                const next = { ...current }

                settled.forEach((item) => {
                    if (item.status === 'fulfilled') {
                        next[item.value.id] =
                            item.value.name
                    }
                })

                return next
            })
        }

        void resolveNames()

        return () => {
            controller.abort()
        }
    }, [
        appliedOrganizationId,
        draftOrganizationId,
        enabled,
        names,
    ])

    const options = useMemo(
        () => [
            ALL_ORGANIZATIONS_OPTION,
            ...results.map(
                toOrganizationOption,
            ),
        ],
        [results],
    )

    return {
        query,
        setQuery,
        options,
        loading,
        error,
        draftOrganizationName:
            names[
                draftOrganizationId.trim()
            ] ?? '',
        appliedOrganizationName:
            names[
                appliedOrganizationId.trim()
            ] ?? '',
    }
}

function mergeNames(
    current: Record<string, string>,
    organizations: OrganizationDirectoryItem[],
): Record<string, string> {
    const next = { ...current }

    organizations.forEach((organization) => {
        next[organization.id] = organization.name
    })

    return next
}

function toOrganizationOption(
    organization: OrganizationDirectoryItem,
): UsageFilterOption {
    const typeLabel =
        organization.type === 'PLATFORM'
            ? 'Платформенная'
            : 'Клиентская'

    return {
        value: organization.id,
        label: organization.name,
        description:
            `${typeLabel} · ${organization.id}`,
        status: organization.enabled
            ? 'Включена'
            : 'Отключена',
        statusTone: organization.enabled
            ? 'positive'
            : 'muted',
    }
}
