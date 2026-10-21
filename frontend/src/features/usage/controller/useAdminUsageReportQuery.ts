// frontend/src/features/usage/controller/useAdminUsageReportQuery.ts

import {
    useEffect,
    useMemo,
    useState,
} from 'react'
import {
    getApiErrorMessage,
} from '../../../shared/api/http'
import {
    isAbortError,
    toUsageFilter,
} from '../lib/adminUsage.helpers'
import type {
    UsageReportTab,
} from '../lib/adminUsage.helpers'
import {
    loadUsageReport,
} from '../lib/adminUsageReportLoader'
import type {
    UsageReportRows,
} from '../lib/adminUsageReportLoader'

type QueryState = {
    key: string
    loading: boolean
    error: string
    rows: UsageReportRows
    hasNext: boolean
    hasPrevious: boolean
}

type UseAdminUsageReportQueryInput = {
    enabled: boolean
    userId: string
    userOrganizationId: string
    tab: UsageReportTab
    page: number
    dateFrom: string
    dateTo: string
    model: string
    organizationId: string
    isSuperAdmin: boolean
    reloadToken: number
}

export function useAdminUsageReportQuery({
    enabled,
    userId,
    userOrganizationId,
    tab,
    page,
    dateFrom,
    dateTo,
    model,
    organizationId,
    isSuperAdmin,
    reloadToken,
}: UseAdminUsageReportQueryInput) {
    const queryKey = useMemo(
        () => JSON.stringify([
            userId,
            userOrganizationId,
            tab,
            page,
            dateFrom,
            dateTo,
            model,
            isSuperAdmin
                ? organizationId
                : '',
            reloadToken,
        ]),
        [
            dateFrom,
            dateTo,
            isSuperAdmin,
            model,
            organizationId,
            page,
            reloadToken,
            tab,
            userId,
            userOrganizationId,
        ],
    )

    const [state, setState] =
        useState<QueryState>(() => ({
            key: queryKey,
            loading: true,
            error: '',
            rows: [],
            hasNext: false,
            hasPrevious: false,
        }))

    useEffect(() => {
        if (!enabled) {
            return
        }

        const controller = new AbortController()

        async function load() {
            setState({
                key: queryKey,
                loading: true,
                error: '',
                rows: [],
                hasNext: false,
                hasPrevious: false,
            })

            try {
                const normalized = toUsageFilter(
                    {
                        dateFrom,
                        dateTo,
                        model,
                        organizationId,
                    },
                    isSuperAdmin,
                )

                const result = await loadUsageReport({
                    tab,
                    page,
                    filter: normalized.filter,
                    organizationId:
                        normalized.organizationId
                            || null,
                    superAdmin: isSuperAdmin,
                    signal: controller.signal,
                })

                if (controller.signal.aborted) {
                    return
                }

                setState({
                    key: queryKey,
                    loading: false,
                    error: '',
                    rows: result.rows,
                    hasNext: result.hasNext,
                    hasPrevious:
                        result.hasPrevious,
                })
            } catch (loadError) {
                if (
                    controller.signal.aborted
                    || isAbortError(loadError)
                ) {
                    return
                }

                setState({
                    key: queryKey,
                    loading: false,
                    error: getApiErrorMessage(
                        loadError,
                        'Не удалось загрузить статистику использования.',
                    ),
                    rows: [],
                    hasNext: false,
                    hasPrevious: false,
                })
            }
        }

        void load()

        return () => {
            controller.abort()
        }
    }, [
        dateFrom,
        dateTo,
        enabled,
        isSuperAdmin,
        model,
        organizationId,
        page,
        queryKey,
        tab,
    ])

    const current = state.key === queryKey

    return {
        rows: current
            ? state.rows
            : [],
        loading: !current
            || state.loading,
        error: current
            ? state.error
            : '',
        hasNext: current
            ? state.hasNext
            : false,
        hasPrevious: current
            ? state.hasPrevious
            : false,
    }
}
