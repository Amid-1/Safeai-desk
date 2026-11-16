// frontend/src/features/usage/ui/AdminUsageControls.tsx
import { UsageFilterCombobox, type UsageFilterOption } from './UsageFilterCombobox'
import { TabButton } from './UsageReportView'
import { formatIsoDate, type UsageReportTab } from '../lib/adminUsage.helpers'

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
    appliedUserId?: string
    appliedUserLabel?: string
    appliedOrganizationId?: string
    onClearFilter?: (filter: 'period' | 'model' | 'user' | 'organization') => void
    loading: boolean
    filtersOpen: boolean
    onToggleFilters: () => void
    onDateFromChange: (value: string) => void
    onDateToChange: (value: string) => void
    onModelChange: (value: string) => void
    onOrganizationQueryChange: (value: string) => void
    onOrganizationChange: (value: string, option: UsageFilterOption | null) => void
    onApplyFilters: () => void
    onResetFilters: () => void
    onSelectTab: (tab: UsageReportTab) => void
}

export function AdminUsageControls(p: AdminUsageControlsProps) {
    const period = p.appliedDateFrom === p.appliedDateTo
        ? formatIsoDate(p.appliedDateFrom)
        : `${formatIsoDate(p.appliedDateFrom)} — ${formatIsoDate(p.appliedDateTo)}`
    const chip = (label: string, filter: 'period' | 'model' | 'user' | 'organization', action: string) =>
        <span className="usage-filter-chip">{label}
            {p.onClearFilter && <button type="button" aria-label={action} title={action}
                disabled={p.filtersDirty || p.loading} onClick={() => p.onClearFilter?.(filter)}>×</button>}
        </span>
    return (
        <div className="usage-page__upper">
            <header className="usage-dashboard-header">
                <div>
                    <span className="usage-eyebrow">АНАЛИТИКА И СТОИМОСТЬ</span>
                    <h1>Использование AI</h1>
                </div>
                <div className="usage-scope" aria-label="Область отчёта">
                    <span>Область отчёта</span><strong>{p.scopeLabel}</strong>
                </div>
            </header>
            <section className="usage-filters" aria-label="Период и фильтры">
                <div className="usage-filter-bar">
                    <div className="usage-applied-filters" aria-label="Применённые фильтры">
                        <strong>Период и фильтры</strong>
                        {chip(period, 'period', 'Сбросить период до последних 30 дней')}
                        {p.appliedModel ? chip(p.appliedModel, 'model', 'Сбросить фильтр по модели') : <span>Все модели</span>}
                        {p.appliedUserId && chip(p.appliedUserLabel || p.appliedUserId, 'user', 'Сбросить фильтр по пользователю')}
                        {p.isSuperAdmin && p.appliedOrganizationId && chip(p.scopeLabel, 'organization', 'Сбросить фильтр по организации')}
                        <span title="Календарные дни UTC, обе даты включены">UTC</span>
                    </div>
                    <button type="button" className="secondary-button" aria-expanded={p.filtersOpen}
                        aria-controls="usage-filter-editor" onClick={p.onToggleFilters}>
                        {p.filtersOpen ? 'Свернуть фильтры' : 'Изменить фильтры'}
                    </button>
                </div>
                <div id="usage-filter-editor" className="usage-filter-editor" hidden={!p.filtersOpen}>
                    <div className={`usage-filter-grid${p.isSuperAdmin ? ' usage-filter-grid--org' : ''}`}>
                        <label>Начало периода
                            <input type="date" value={p.draftDateFrom}
                                onChange={event => p.onDateFromChange(event.target.value)} />
                        </label>
                        <label>Конец периода
                            <input type="date" value={p.draftDateTo}
                                onChange={event => p.onDateToChange(event.target.value)} />
                        </label>
                        <UsageFilterCombobox id="usage-model-filter" label="Модель"
                            value={p.draftModel} options={p.modelPickerOptions}
                            placeholder="Все модели" searchPlaceholder="Найти модель"
                            emptyMessage="В выбранном периоде модели не найдены." maxQueryLength={100}
                            loading={p.modelOptionsLoading} error={p.modelOptionsError}
                            onChange={p.onModelChange} />
                        {p.isSuperAdmin && <UsageFilterCombobox id="usage-organization-filter" label="Организация"
                            value={p.draftOrganizationId}
                            selectedLabel={p.draftOrganizationId ? p.draftOrganizationName || p.draftOrganizationId : 'Все организации'}
                            options={p.organizationOptions} placeholder="Все организации"
                            searchPlaceholder="Найти по названию или UUID"
                            emptyMessage="Организации по запросу не найдены." maxQueryLength={255}
                            loading={p.organizationSearchPending} error={p.organizationSearchError}
                            filterLocally={false} onQueryChange={p.onOrganizationQueryChange}
                            onChange={p.onOrganizationChange} />}
                    </div>
                    <div className="usage-filter-actions">
                        <small>Календарные дни UTC. Обе даты включены.</small>
                        <button type="button" disabled={p.loading} onClick={p.onApplyFilters}>Показать отчёт</button>
                        <button type="button" className="secondary-button" disabled={p.loading}
                            onClick={p.onResetFilters}>Сбросить · 30 дней</button>
                    </div>
                </div>
                {p.filtersDirty && <p className="usage-filter-dirty" role="status">
                    Фильтры изменены — показан последний применённый отчёт.
                </p>}
                {p.filterError && <p className="usage-export-error" role="alert">{p.filterError}</p>}
            </section>
            <nav className="usage-tabs" role="tablist" aria-label="Вид отчёта">
                {([
                    ['summary', 'Детализация'], ['users', 'По пользователям'],
                    ['models', 'По моделям'], ['daily', 'По дням'],
                ] as const).map(([name, label]) => <TabButton key={name} active={p.tab === name}
                    onClick={() => p.onSelectTab(name)}>{label}</TabButton>)}
            </nav>
        </div>
    )
}
