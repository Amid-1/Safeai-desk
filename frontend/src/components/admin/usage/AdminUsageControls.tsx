// frontend/src/components/admin/usage/AdminUsageControls.tsx

import type {
    ChangeEvent,
} from 'react'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../../StateBlock'
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
} from '../../../pages/adminUsage.helpers'
import type {
    UsageReportTab,
} from '../../../pages/adminUsage.helpers'

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
    loading: boolean
    error: string
    showEmptyReport: boolean

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
    loading,
    error,
    showEmptyReport,
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
    return (
        <div className="usage-page__upper">
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
                            Обе даты включены в отчёт.
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

                    {tab === 'summary' && (
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
                            helperText="Показываются точные модели, встречавшиеся в выбранном периоде и области отчёта."
                            onChange={(value) => {
                                onModelChange(value)
                            }}
                        />
                    )}

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
                    <h2 id="usage-report-title">
                        {reportTitle(tab)}
                    </h2>

                    <p className="muted">
                        {reportDescription(tab)}
                    </p>
                </div>

                <span
                    className="resizable-scroll-region__upper-boundary-marker"
                    data-resizable-upper-boundary
                    aria-hidden="true"
                />

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
