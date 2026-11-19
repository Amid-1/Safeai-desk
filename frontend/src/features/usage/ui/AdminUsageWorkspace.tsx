// frontend/src/features/usage/ui/AdminUsageWorkspace.tsx
import { useState } from 'react'
import { UsageTableViewport } from './UsageTableViewport'
import { AdminUsageControls } from './AdminUsageControls'
import { UsageReportFooter, UsageRows } from './UsageReportView'
import { useAdminUsageReport } from '../controller/useAdminUsageReport'
import { reportTitle, reportDescription } from '../lib/adminUsage.helpers'
import type { UsageDailySummary } from '../api/usageApi'
import { UsageKpis } from './UsageKpis'
import { UsageDailyChart, type UsageDailyMetric } from './UsageDailyChart'
import { UsageBreakdownChart } from './UsageBreakdownChart'
import { downloadUsageCsv } from './usageCsv'
import { EmptyState, ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import { trimDecimal } from '../lib/adminUsagePresentation'
import { UsageOrganizationComparison } from './UsageOrganizationComparison'

export function AdminUsageWorkspace() {
    const report = useAdminUsageReport()
    const [view, setView] = useState<'table' | 'chart'>('table')
    const [filtersOpen, setFiltersOpen] = useState(false)
    const [expanded, setExpanded] = useState(false)
    const [dailyMetric, setDailyMetric] = useState<UsageDailyMetric>('tokens')
    const [exportError, setExportError] = useState('')
    const [compareOrganizations, setCompareOrganizations] = useState(false)
    const canCompare = report.isSuperAdmin && !report.appliedOrganizationId
    const hasRows = !report.loading && !report.error && report.rows.length > 0
    const hasMore = report.hasPrevious || report.hasNext
    const paged = report.tab === 'summary' || report.tab === 'users'
    const separateTotals = report.tab !== 'daily'
    const chartExpanded = view === 'chart' && expanded && hasRows
    const dataKey = JSON.stringify([
        report.tab, report.page, report.appliedDateFrom, report.appliedDateTo,
        report.appliedModel, report.appliedUserId, report.appliedOrganizationId, view,
    ])

    function exportCsv() {
        try {
            downloadUsageCsv(report.tab, report.rows)
            setExportError('')
        } catch {
            setExportError('Не удалось создать CSV. Попробуйте повторно.')
        }
    }

    return (
        <div className={`page usage-page${chartExpanded ? ' usage-page--expanded' : ''}`}
            onKeyDown={event => {
                if (event.key === 'Escape' && chartExpanded && !event.defaultPrevented) setExpanded(false)
            }}>
            <AdminUsageControls
                tab={report.tab} isSuperAdmin={report.isSuperAdmin} scopeLabel={report.scopeLabel}
                draftDateFrom={report.draftDateFrom} draftDateTo={report.draftDateTo}
                draftModel={report.draftModel} draftOrganizationId={report.draftOrganizationId}
                draftOrganizationName={report.draftOrganizationName}
                organizationOptions={report.organizationOptions}
                organizationSearchPending={report.organizationSearchPending}
                organizationSearchError={report.organizationSearchError}
                modelPickerOptions={report.modelPickerOptions}
                modelOptionsLoading={report.modelOptionsLoading} modelOptionsError={report.modelOptionsError}
                filterError={report.filterError} filtersDirty={report.filtersDirty}
                appliedModel={report.appliedModel} appliedDateFrom={report.appliedDateFrom}
                appliedDateTo={report.appliedDateTo} loading={report.loading}
                appliedUserId={report.appliedUserId} appliedUserLabel={report.appliedUserLabel}
                appliedOrganizationId={report.appliedOrganizationId} onClearFilter={report.clearFilter}
                filtersOpen={filtersOpen}
                onToggleFilters={() => setFiltersOpen(open => !open)}
                onDateFromChange={report.changeDraftDateFrom}
                onDateToChange={report.changeDraftDateTo}
                onModelChange={report.changeDraftModel}
                onOrganizationQueryChange={report.setOrganizationQuery}
                onOrganizationChange={report.changeDraftOrganization}
                onApplyFilters={() => { setExportError(''); report.applyFilters() }}
                onResetFilters={() => { setExportError(''); report.resetFilters() }}
                onSelectTab={tab => { setExportError(''); report.selectTab(tab) }}
            />
            <UsageKpis rows={separateTotals ? report.totals.rows : hasRows ? report.rows : []} tab={report.tab}
                hasMore={false} appliedModel={report.appliedModel}
                dailyTotals
                status={separateTotals && report.totals.loading ? 'Загрузка общих итогов…'
                    : separateTotals && report.totals.error ? 'Общие итоги недоступны' : undefined} />
            {report.budget.scoped && <section className="usage-filter-summary" aria-label="Бюджет организации">
                <strong>Месячный бюджет организации: </strong>
                {report.budget.loading ? 'Загрузка…' : report.budget.error || !report.budget.policy ? 'Недоступен'
                    : report.budget.policy.monthlyBudgetUsd === null ? 'Не задан'
                        : `${trimDecimal(report.budget.policy.monthlyBudgetUsd)} USD`}
                {report.budget.policy && <> · {report.budget.policy.policyEnabled ? 'Правила включены' : 'Правила отключены'} ·
                    {' '}версия {report.budget.policy.policyVersion}. Календарный месяц UTC, независимо от периода отчёта.
                    <div>Известные затраты: {trimDecimal(report.budget.policy.knownActualUsd)} USD · Резерв: {trimDecimal(report.budget.policy.reservedUsd)} USD ·
                        {' '}Остаток: {report.budget.policy.remainingUsd === null
                            ? report.budget.policy.status === 'UNKNOWN' || report.budget.policy.status === 'EXCEEDED_LOWER_BOUND'
                                ? 'Неизвестен — есть обязательства без цены' : 'Не рассчитывается'
                            : `${trimDecimal(report.budget.policy.remainingUsd)} USD`}
                        {['EXCEEDED', 'EXCEEDED_LOWER_BOUND'].includes(report.budget.policy.status) && ' · Бюджет превышен'}
                    </div>
                    <small>На {report.budget.policy.asOf} · контроль: {report.budget.policy.enforcement === 'HARD' ? 'жёсткий' : 'мягкий'}. Остаток учитывает резервы, а не только списания.</small>
                </>}
            </section>}
            {canCompare && <div className="usage-filter-summary">
                <button type="button" className="secondary-button" aria-pressed={compareOrganizations} disabled={report.filtersDirty}
                    onClick={() => setCompareOrganizations(value => !value)}>
                    {compareOrganizations ? 'Вернуться к детализации' : 'Сравнить организации'}
                </button>
            </div>}
            {canCompare && compareOrganizations
                ? <UsageOrganizationComparison key={JSON.stringify([report.reportIdentity, report.reloadToken, report.appliedDateFrom,
                    report.appliedDateTo, report.appliedModel, report.appliedUserId])}
                    dateFrom={report.appliedDateFrom} dateTo={report.appliedDateTo} model={report.appliedModel} userId={report.appliedUserId} />
                : <section className={`usage-results-panel usage-results-panel--${view}`} aria-labelledby="usage-report-title">
                <div className="usage-results-toolbar">
                    <div className="usage-results-heading">
                        <h2 id="usage-report-title">{reportTitle(report.tab)}</h2>
                        <p>{reportDescription(report.tab)}</p>
                    </div>
                    <div className="usage-results-actions">
                        <div className="usage-view-switch" role="group" aria-label="Представление отчёта">
                            <button type="button" aria-pressed={view === 'table'}
                                onClick={() => { setView('table'); setExpanded(false) }}>Таблица</button>
                            <button type="button" aria-pressed={view === 'chart'}
                                onClick={() => setView('chart')}>График</button>
                        </div>
                        <button type="button" className="secondary-button" disabled={!hasRows}
                            title={paged ? 'До 50 строк текущей страницы' : 'Все строки текущего ограниченного отчёта'}
                            onClick={exportCsv}>{paged ? 'Скачать CSV страницы' : 'Скачать CSV отчёта'}</button>
                        {paged && <button type="button" className="secondary-button"
                            disabled={!hasRows || report.filtersDirty || report.exportPending}
                            onClick={report.exportFiltered}>CSV по фильтрам (до 100)</button>}
                        {view === 'chart' && <button type="button" className="secondary-button"
                            disabled={!hasRows} title={chartExpanded ? 'Обычный размер графика' : 'Увеличить область графика'}
                            aria-pressed={chartExpanded} onClick={() => setExpanded(value => !value)}>
                            {chartExpanded ? 'Свернуть' : 'Развернуть'}
                        </button>}
                    </div>
                </div>
                {exportError && <p role="alert" className="usage-export-error">{exportError}</p>}
                {report.filteredExportError && <p role="alert" className="usage-export-error">{report.filteredExportError}</p>}
                <UsageTableViewport key={dataKey} table={view === 'table'} hasRows={hasRows} loading={report.loading}>
                    {report.loading ? <LoadingState message="Загрузка отчёта…" />
                        : report.error ? <ErrorState title="Ошибка загрузки" message={report.error}
                            action={<button type="button" onClick={report.reload}>Повторить</button>} />
                        : !hasRows ? <EmptyState title="За этот период использования нет"
                            message="Данные появятся после завершённых обращений к AI. Выберите другой период." />
                        : view === 'table' ? <UsageRows tab={report.tab} rows={report.rows}
                            onInspectModel={report.inspectModel} onInspectDay={report.inspectDay}
                            onInspectUser={report.inspectUser} appliedModel={report.appliedModel}
                            appliedUserId={report.appliedUserId}
                            relatedLinks={report.isSuperAdmin} organizationId={report.appliedOrganizationId}
                            drilldownDisabled={report.filtersDirty || report.loading} />
                        : report.tab === 'daily' ? <UsageDailyChart rows={report.rows as UsageDailySummary[]}
                            dateFrom={report.appliedDateFrom} dateTo={report.appliedDateTo}
                            metric={dailyMetric} onMetricChange={setDailyMetric} />
                        : <UsageBreakdownChart tab={report.tab} rows={report.rows} hasMore={hasMore} />}
                </UsageTableViewport>
                <footer className="usage-results-footer">
                    {!report.loading && !report.error && (hasRows || hasMore)
                        ? <UsageReportFooter tab={report.tab} rowsCount={report.rows.length}
                            page={report.page} hasPrevious={report.hasPrevious} hasNext={report.hasNext}
                            loading={report.loading} onPageChange={page => { setExportError(''); report.goToPage(page) }} />
                        : <span className="usage-footer-status">{report.loading ? 'Загрузка…' : report.error ? 'Отчёт недоступен' : 'Нет записей'}</span>}
                </footer>
            </section>}
        </div>
    )
}
