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
import type { UsageSummaryFilter } from '../api/usageApi'
import { ApiError } from '../../../shared/api/httpErrors'

function organizationReportError(error: unknown): string {
    if (error instanceof ApiError) {
        if (error.status === 404) return 'Сервер не предоставляет отчёт по организациям. Проверьте версию backend и маршрутизацию API.'
        if (error.status === 403) return 'Нет доступа к отчёту по организациям.'
        if (error.status === 429) return 'Слишком много запросов. Повторите позже.'
    }
    return 'Сравнение организаций недоступно. Повторите запрос или обратитесь к администратору.'
}

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
    filterUserId?: string
    organizationId: string
    isSuperAdmin: boolean
    reloadToken: number
    order?: UsageSummaryFilter['order']
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
    filterUserId,
    organizationId,
    isSuperAdmin,
    reloadToken,
    order,
}: UseAdminUsageReportQueryInput) {
    const queryKey = useMemo(
        () => JSON.stringify([
            userId,
            userOrganizationId,
            isSuperAdmin,
            tab,
            page,
            dateFrom,
            dateTo,
            model,
            filterUserId,
            isSuperAdmin
                ? organizationId
                : '',
            reloadToken,
            order,
        ]),
        [
            dateFrom,
            dateTo,
            isSuperAdmin,
            model,
            filterUserId,
            organizationId,
            page,
            reloadToken,
            order,
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
                        userId: filterUserId,
                        organizationId,
                    },
                    isSuperAdmin,
                )

                const result = await loadUsageReport({
                    tab,
                    page,
                    filter: {...normalized.filter, ...(order ? {order} : {})},
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
                    error: tab === 'organizations' ? organizationReportError(loadError) : getApiErrorMessage(
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
        filterUserId,
        isSuperAdmin,
        model,
        organizationId,
        order,
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
