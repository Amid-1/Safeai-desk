// frontend/src/features/usage/ui/AdminUsageControls.tsx

import { useLayoutEffect, useRef, useState } from 'react'
import type { ChangeEvent, ReactNode } from 'react'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../../../shared/ui/StateBlock'
import {
    UsageFilterCombobox,
} from './UsageFilterCombobox'
import type {
    UsageFilterOption,
} from './UsageFilterCombobox'
import {
    TabButton,
} from './UsageReportView'
import {
    reportDescription,
    reportTitle,
} from '../lib/adminUsage.helpers'
import type {
    UsageReportTab,
} from '../lib/adminUsage.helpers'

type AdminUsageControlsProps = {
    tab: UsageReportTab
    isSuperAdmin: boolean
    scopeLabel: string

    draftDateFrom: string
    draftDateTo: string
    draftModel: string
    draftOrganizationId: string
    draftOrganizationName: string

    organizationOptions: UsageFilterOption[]
    organizationSearchPending: boolean
    organizationSearchError: string
    modelPickerOptions: UsageFilterOption[]
    modelOptionsLoading: boolean
    modelOptionsError: string

    filterError: string
    filtersDirty: boolean
    appliedModel: string
    appliedDateFrom: string
    appliedDateTo: string
    loading: boolean
    error: string
    showEmptyReport: boolean
    summaryContent?: ReactNode
    canExpandResults?: boolean
    onExpandResults?: () => void
    onRestoreFilters?: () => void

    onDateFromChange: (value: string) => void
    onDateToChange: (value: string) => void
    onModelChange: (value: string) => void
    onOrganizationQueryChange: (value: string) => void
    onOrganizationChange: (
        value: string,
        option: UsageFilterOption | null,
    ) => void
    onApplyFilters: () => void
    onResetFilters: () => void
    onSelectTab: (tab: UsageReportTab) => void
    onReload: () => void
}

export function AdminUsageControls({
    tab,
    isSuperAdmin,
    scopeLabel,
    draftDateFrom,
    draftDateTo,
    draftModel,
    draftOrganizationId,
    draftOrganizationName,
    organizationOptions,
    organizationSearchPending,
    organizationSearchError,
    modelPickerOptions,
    modelOptionsLoading,
    modelOptionsError,
    filterError,
    filtersDirty,
    appliedModel,
    appliedDateFrom,
    appliedDateTo,
    loading,
    error,
    showEmptyReport,
    summaryContent,
    canExpandResults = false,
    onExpandResults,
    onRestoreFilters,
    onDateFromChange,
    onDateToChange,
    onModelChange,
    onOrganizationQueryChange,
    onOrganizationChange,
    onApplyFilters,
    onResetFilters,
    onSelectTab,
    onReload,
}: AdminUsageControlsProps) {
    const contentRef = useRef<HTMLDivElement>(null)
    const [compact, setCompact] = useState(false)

    // Observe the split pane, not the browser window. The pane may be
    // resized by dragging without changing the viewport dimensions.
    useLayoutEffect(() => {
        const content = contentRef.current
        const pane = content?.closest('.resizable-scroll-region__upper')
        if (!(pane instanceof HTMLElement)) return
        const measure = () => {
            const height = pane.getBoundingClientRect().height
            // Keep full controls in the DOM: only their presentation changes.
            setCompact(height > 0 && height < 220)
        }
        measure()
        if (typeof ResizeObserver === 'undefined') {
            window.addEventListener('resize', measure)
            return () => window.removeEventListener('resize', measure)
        }
        const observer = new ResizeObserver(measure)
        observer.observe(pane)
        return () => observer.disconnect()
    }, [])

    return (
        <div
            className="usage-page__upper"
            ref={contentRef}
            data-compact={compact ? 'true' : 'false'}
        >
            <div className="usage-compact-rail" hidden={!compact}>
                <div className="usage-compact-rail__summary">
                    <strong>Использование AI · {reportTitle(tab)}</strong>
                    <span>{appliedDateFrom} — {appliedDateTo} UTC · {scopeLabel}</span>
                </div>
                <nav
                    className="usage-compact-rail__tabs"
                    role="tablist"
                    aria-label="Переключить отчет в расширенной области"
                >
                    {([
                        ['summary', 'Сводка'],
                        ['users', 'По пользователям'],
                        ['models', 'По моделям'],
                        ['daily', 'По дням'],
                    ] as const).map(([name, label]) => (
                        <TabButton
                            key={name}
                            active={tab === name}
                            onClick={() => onSelectTab(name)}
                        >
                            {label}
                        </TabButton>
                    ))}
                </nav>
                {onRestoreFilters && (
                    <button
                        className="secondary-button usage-compact-rail__restore"
                        type="button"
                        onClick={onRestoreFilters}
                    >
                        Показать фильтры
                    </button>
                )}
            </div>
            <header className="usage-page__header page-hero page-hero--usage">
                <div>
                    <span className="page-hero__eyebrow">
                        Стоимость и прозрачность
                    </span>

                    <h1>
                        Использование AI
                    </h1>

                    <p className="muted">
                        Контролируйте расход токенов, стоимость и качество данных.
                    </p>
                </div>

                <div
                    className="usage-scope"
                    aria-label="Область отчёта"
                >
                    <span className="usage-scope__label">
                        Отчёт
                    </span>

                    <strong>
                        {scopeLabel}
                    </strong>
                </div>
            </header>

            <section
                className="card usage-filters"
                aria-labelledby="usage-filters-title"
            >
                <div className="usage-section-heading">
                    <div>
                        <h2 id="usage-filters-title">
                            Период и фильтры
                        </h2>

                        <p className="muted">
                            Календарные дни UTC (00:00–24:00). Начальная и конечная даты включены в отчёт.
                        </p>
                    </div>
                </div>

                <div className="usage-filter-grid">
                    <label>
                        С

                        <input
                            type="date"
                            value={draftDateFrom}
                            onChange={(event: ChangeEvent<HTMLInputElement>) => {
                                onDateFromChange(
                                    event.target.value,
                                )
                            }}
                        />
                    </label>

                    <label>
                        По

                        <input
                            type="date"
                            value={draftDateTo}
                            onChange={(event: ChangeEvent<HTMLInputElement>) => {
                                onDateToChange(
                                    event.target.value,
                                )
                            }}
                        />
                    </label>

                    <UsageFilterCombobox
                            id="usage-model-filter"
                            label="Модель"
                            value={draftModel}
                            options={modelPickerOptions}
                            placeholder="Все модели"
                            searchPlaceholder="Найти модель"
                            emptyMessage="В выбранном периоде модели не найдены."
                            maxQueryLength={100}
                            loading={modelOptionsLoading}
                            error={modelOptionsError}
                            helperText="Фильтр применяется сервером ко всем четырем видам отчета. Список моделей соответствует указанному периоду и организации."
                            onChange={(value) => {
                                onModelChange(value)
                            }}
                    />

                    {isSuperAdmin && (
                        <UsageFilterCombobox
                            id="usage-organization-filter"
                            label="Организация"
                            value={draftOrganizationId}
                            selectedLabel={
                                draftOrganizationId
                                    ? draftOrganizationName
                                        || draftOrganizationId
                                    : 'Все организации'
                            }
                            options={organizationOptions}
                            placeholder="Все организации"
                            searchPlaceholder="Найти по названию или UUID"
                            emptyMessage="Организации по запросу не найдены."
                            maxQueryLength={255}
                            loading={organizationSearchPending}
                            error={organizationSearchError}
                            filterLocally={false}
                            helperText="Выберите организацию по названию или UUID. В запрос автоматически передаётся её UUID."
                            onQueryChange={
                                onOrganizationQueryChange
                            }
                            onChange={
                                onOrganizationChange
                            }
                        />
                    )}
                </div>

                {filterError && (
                    <div
                        className="error"
                        role="alert"
                    >
                        {filterError}
                    </div>
                )}

                <div className="usage-filter-actions">
                    {filtersDirty && (
                        <span role="status" className="usage-filter-dirty">
                            Фильтры изменены — результаты еще не обновлены.
                        </span>
                    )}
                    <button
                        type="button"
                        disabled={loading}
                        onClick={onApplyFilters}
                    >
                        Показать
                    </button>

                    <button
                        type="button"
                        className="secondary-button"
                        disabled={loading}
                        onClick={onResetFilters}
                    >
                        Последние 30 дней
                    </button>
                </div>
            </section>

            <section
                className="usage-report"
                aria-labelledby="usage-report-title"
            >
                <div
                    className="usage-tabs"
                    role="tablist"
                    aria-label="Вид отчёта"
                >
                    <TabButton
                        active={tab === 'summary'}
                        onClick={() => {
                            onSelectTab('summary')
                        }}
                    >
                        Сводка
                    </TabButton>

                    <TabButton
                        active={tab === 'users'}
                        onClick={() => {
                            onSelectTab('users')
                        }}
                    >
                        По пользователям
                    </TabButton>

                    <TabButton
                        active={tab === 'models'}
                        onClick={() => {
                            onSelectTab('models')
                        }}
                    >
                        По моделям
                    </TabButton>

                    <TabButton
                        active={tab === 'daily'}
                        onClick={() => {
                            onSelectTab('daily')
                        }}
                    >
                        По дням
                    </TabButton>
                </div>

                <div className="usage-report-heading">
                    <div className="usage-report-heading__title">
                    <h2 id="usage-report-title">
                        {reportTitle(tab)}
                    </h2>

                    <p className="muted">
                        {reportDescription(tab)}
                    </p>
                    {tab === 'daily' && (
                        <p className="usage-chart-discovery">
                            Ниже фильтров отображаются KPI и график «Динамика по дням UTC»; таблица с точными значениями сохранена.
                        </p>
                    )}
                    </div>
                    {canExpandResults && onExpandResults && (
                        <button
                            type="button"
                            className="secondary-button usage-expand-results"
                            onClick={onExpandResults}
                            title="Развернуть результаты почти до навигации; фильтры можно вернуть одним нажатием"
                        >
                            Развернуть результаты
                        </button>
                    )}
                </div>

                <div className="usage-applied-filters" aria-label="Примененные фильтры">
                    <span>Период UTC: {appliedDateFrom} — {appliedDateTo}</span>
                    <span>Модель: {appliedModel || 'Все модели'}</span>
                    <span>Область: {scopeLabel}</span>
                </div>

                <span
                    className="resizable-scroll-region__upper-boundary-marker"
                    data-resizable-upper-boundary
                    aria-hidden="true"
                />

                {summaryContent}

                {loading && (
                    <LoadingState
                        message="Загрузка статистики использования..."
                    />
                )}

                {!loading && error && (
                    <ErrorState
                        title="Ошибка загрузки"
                        message={error}
                        action={(
                            <button
                                type="button"
                                onClick={onReload}
                            >
                                Повторить
                            </button>
                        )}
                    />
                )}

                {showEmptyReport && (
                    <EmptyState
                        title="За этот период использования нет"
                        message="Данные появятся после завершённых обращений к AI. Выберите другой период или покажите последние 30 дней."
                    />
                )}
            </section>
        </div>
    )
}
