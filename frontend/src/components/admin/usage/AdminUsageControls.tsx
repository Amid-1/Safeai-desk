import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../../StateBlock'
import {
    TabButton,
} from './UsageReportView'
import {
    reportDescription,
    reportTitle,
} from '../../../pages/adminUsagePageSupport'
import type {
    UsageReportTab,
} from '../../../pages/adminUsagePageSupport'

type AdminUsageControlsProps = {
    tab: UsageReportTab
    isSuperAdmin: boolean
    effectiveOrganizationId: string | null
    draftDateFrom: string
    draftDateTo: string
    draftModel: string
    draftOrganizationId: string
    filterError: string
    loading: boolean
    error: string
    showEmptyReport: boolean
    onDateFromChange: (value: string) => void
    onDateToChange: (value: string) => void
    onModelChange: (value: string) => void
    onOrganizationIdChange: (value: string) => void
    onApplyFilters: () => void
    onResetFilters: () => void
    onSelectTab: (tab: UsageReportTab) => void
    onReload: () => void
}

export function AdminUsageControls({
    tab,
    isSuperAdmin,
    effectiveOrganizationId,
    draftDateFrom,
    draftDateTo,
    draftModel,
    draftOrganizationId,
    filterError,
    loading,
    error,
    showEmptyReport,
    onDateFromChange,
    onDateToChange,
    onModelChange,
    onOrganizationIdChange,
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

                    <h1>Использование AI</h1>

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
                        {isSuperAdmin
                            ? (
                                effectiveOrganizationId
                                    ? 'Выбранная организация'
                                    : 'Все организации'
                            )
                            : 'Моя организация'}
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
                            onChange={(event) => {
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
                            onChange={(event) => {
                                onDateToChange(
                                    event.target.value,
                                )
                            }}
                        />
                    </label>

                    {tab === 'summary' && (
                        <label>
                            Модель

                            <input
                                value={draftModel}
                                onChange={(event) => {
                                    onModelChange(
                                        event.target.value,
                                    )
                                }}
                                maxLength={100}
                                placeholder="Все модели"
                            />
                        </label>
                    )}

                    {isSuperAdmin && (
                        <label>
                            Организация

                            <input
                                value={
                                    draftOrganizationId
                                }
                                onChange={(event) => {
                                    onOrganizationIdChange(
                                        event.target.value,
                                    )
                                }}
                                maxLength={36}
                                placeholder="UUID или все организации"
                            />

                            <small className="muted">
                                Оставьте пустым для общего отчёта.
                            </small>
                        </label>
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

                {loading && (
                    <LoadingState message="Загрузка статистики использования..." />
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
