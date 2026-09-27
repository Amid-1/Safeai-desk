// frontend/src/pages/useAdminUsageModelOptions.ts

import {
    useEffect,
    useMemo,
    useState,
} from 'react'
import {
    getOrganizationUsageModels,
    getUsageByModels,
} from '../api/usageApi'
import {
    getApiErrorMessage,
} from '../api/http'
import type {
    UsageFilterOption,
} from '../components/admin/usage/UsageFilterCombobox'
import {
    isAbortError,
    toUsageFilter,
} from './adminUsage.helpers'

const LOAD_DEBOUNCE_MS = 120

const ALL_MODELS_OPTION: UsageFilterOption = {
    value: '',
    label: 'Все модели',
    description: 'Без фильтра по модели',
}

type UseAdminUsageModelOptionsInput = {
    enabled: boolean
    isSuperAdmin: boolean
    dateFrom: string
    dateTo: string
    organizationId: string
}

export function useAdminUsageModelOptions({
    enabled,
    isSuperAdmin,
    dateFrom,
    dateTo,
    organizationId,
}: UseAdminUsageModelOptionsInput) {
    const [options, setOptions] =
        useState<UsageFilterOption[]>([])
    const [loading, setLoading] =
        useState(false)
    const [error, setError] =
        useState('')

    useEffect(() => {
        if (!enabled) {
            setOptions([])
            setLoading(false)
            setError('')
            return
        }

        let normalized: ReturnType<
            typeof toUsageFilter
        >

        try {
            normalized = toUsageFilter(
                {
                    dateFrom,
                    dateTo,
                    model: '',
                    organizationId,
                },
                isSuperAdmin,
            )
        } catch {
            setOptions([])
            setLoading(false)
            setError('')
            return
        }

        const controller = new AbortController()
        const timer = window.setTimeout(
            () => {
                async function load() {
                    setLoading(true)
                    setError('')

                    try {
                        const dateFilter = {
                            dateFrom:
                                normalized.filter.dateFrom,
                            dateTo:
                                normalized.filter.dateTo,
                        }

                        const models =
                            isSuperAdmin
                            && normalized.organizationId
                                ? await getOrganizationUsageModels(
                                    normalized.organizationId,
                                    dateFilter,
                                    {
                                        signal:
                                            controller.signal,
                                    },
                                )
                                : await getUsageByModels(
                                    dateFilter,
                                    {
                                        signal:
                                            controller.signal,
                                    },
                                )

                        if (controller.signal.aborted) {
                            return
                        }

                        setOptions(
                            uniqueModelOptions(
                                models.map(
                                    (model) => model.model,
                                ),
                            ),
                        )
                    } catch (loadError) {
                        if (
                            controller.signal.aborted
                            || isAbortError(loadError)
                        ) {
                            return
                        }

                        setOptions([])
                        setError(
                            getApiErrorMessage(
                                loadError,
                                'Не удалось загрузить список моделей.',
                            ),
                        )
                    } finally {
                        if (!controller.signal.aborted) {
                            setLoading(false)
                        }
                    }
                }

                void load()
            },
            LOAD_DEBOUNCE_MS,
        )

        return () => {
            window.clearTimeout(timer)
            controller.abort()
        }
    }, [
        dateFrom,
        dateTo,
        enabled,
        isSuperAdmin,
        organizationId,
    ])

    const pickerOptions = useMemo(
        () => [
            ALL_MODELS_OPTION,
            ...options,
        ],
        [options],
    )

    return {
        options: pickerOptions,
        loading,
        error,
    }
}

function uniqueModelOptions(
    models: string[],
): UsageFilterOption[] {
    return Array.from(
        new Set(models),
    )
        .sort((left, right) =>
            left.localeCompare(
                right,
                'ru-RU',
                {
                    sensitivity: 'base',
                },
            ),
        )
        .map((model) => ({
            value: model,
            label: model,
            description:
                'Использовалась в выбранном периоде',
        }))
}
