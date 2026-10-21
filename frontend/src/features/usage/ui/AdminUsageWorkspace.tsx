// frontend/src/features/usage/ui/AdminUsageWorkspace.tsx
// Представление отчёта: отображение строк, экспорт, графики и рабочая область.

import ResizableScrollRegion
    from '../../../shared/ui/ResizableScrollRegion'
import {
    AdminUsageControls,
} from './AdminUsageControls'
import {
    UsageReportFooter,
    UsageRows,
} from './UsageReportView'
import {
    useAdminUsageReport,
} from '../controller/useAdminUsageReport'
import { useState } from 'react'
import { useUsageWorkspace } from '../controller/useUsageWorkspace'
import type { UsageDailySummary } from '../api/usageApi'
import { UsageKpis } from './UsageKpis'
import { UsageDailyChart } from './UsageDailyChart'
import { UsageBreakdownChart } from './UsageBreakdownChart'
import { downloadUsageCsv } from './usageCsv'

export function AdminUsageWorkspace() {
    const report = useAdminUsageReport()
    const [exportError, setExportError] = useState('')
    const showReportRows =
        !report.loading
        && !report.error
        && report.rows.length > 0

    const showEmptyReport =
        !report.loading
        && !report.error
        && report.rows.length === 0

    const {
        focusedResults, pageRef, resultsRef, showLowerRequest, showUpperRequest,
        fittedResultsHeight, onWorkspacePointerDownCapture, onWorkspaceKeyDownCapture,
        expandResults, restoreFilters,
    } = useUsageWorkspace({ showReportRows, tab: report.tab, rowsLength: report.rows.length })

    const viewportResetKey = [
        report.tab,
        report.page,
        report.appliedDateFrom,
        report.appliedDateTo,
        report.appliedModel,
        report.scopeLabel,
    ].join('|')

    return (
        <div
            ref={pageRef}
            onPointerDownCapture={onWorkspacePointerDownCapture}
            onKeyDownCapture={onWorkspaceKeyDownCapture}
            className={focusedResults
                ? 'page usage-page usage-page--focused'
                : 'page usage-page usage-page--flow'}
        >
            <ResizableScrollRegion
                storageKey="safeai:usage-report-height:v4"
                label="область результатов использования AI"
                showLowerRequest={showLowerRequest}
                showUpperRequest={showUpperRequest}
                viewportResetKey={viewportResetKey}
                upper={(
                    <AdminUsageControls
                        tab={report.tab}
                        isSuperAdmin={
                            report.isSuperAdmin
                        }
                        scopeLabel={
                            report.scopeLabel
                        }
                        draftDateFrom={
                            report.draftDateFrom
                        }
                        draftDateTo={
                            report.draftDateTo
                        }
                        draftModel={
                            report.draftModel
                        }
                        draftOrganizationId={
                            report.draftOrganizationId
                        }
                        draftOrganizationName={
                            report.draftOrganizationName
                        }
                        organizationOptions={
                            report.organizationOptions
                        }
                        organizationSearchPending={
                            report.organizationSearchPending
                        }
                        organizationSearchError={
                            report.organizationSearchError
                        }
                        modelPickerOptions={
                            report.modelPickerOptions
                        }
                        modelOptionsLoading={
                            report.modelOptionsLoading
                        }
                        modelOptionsError={
                            report.modelOptionsError
                        }
                        filterError={
                            report.filterError
                        }
                        filtersDirty={report.filtersDirty}
                        appliedModel={report.appliedModel}
                        appliedDateFrom={report.appliedDateFrom}
                        appliedDateTo={report.appliedDateTo}
                        loading={report.loading}
                        error={report.error}
                        showEmptyReport={
                            showEmptyReport
                        }
                        onDateFromChange={
                            report.changeDraftDateFrom
                        }
                        onDateToChange={
                            report.changeDraftDateTo
                        }
                        onModelChange={
                            report.changeDraftModel
                        }
                        onOrganizationQueryChange={
                            report.setOrganizationQuery
                        }
                        onOrganizationChange={
                            report.changeDraftOrganization
                        }
                        onApplyFilters={
                            report.applyFilters
                        }
                        onResetFilters={
                            report.resetFilters
                        }
                        onSelectTab={
                            report.selectTab
                        }
                        onReload={report.reload}
                        canExpandResults={showReportRows}
                        onExpandResults={expandResults}
                        onRestoreFilters={restoreFilters}
                    />
                )}
                autoFitHeight={fittedResultsHeight}
                footer={
                    showReportRows
                        ? (
                            <UsageReportFooter
                                tab={report.tab}
                                rowsCount={
                                    report.rows.length
                                }
                                page={report.page}
                                hasPrevious={
                                    report.hasPrevious
                                }
                                hasNext={
                                    report.hasNext
                                }
                                loading={
                                    report.loading
                                }
                                onPageChange={
                                    report.goToPage
                                }
                            />
                        )
                        : null
                }
                lowerClassName="card table-card usage-report-card"
                viewportClassName="usage-table-scroll"
                defaultHeight={370}
                minHeight={170}
                maxHeight={4096}
                minUpperHeight={92}
            >
                {showReportRows ? (
                    <div ref={resultsRef} className="usage-report-results">
                        <div className="usage-report-insights">

                                <UsageKpis rows={report.rows} tab={report.tab} />
                                {report.tab === 'daily' ? (
                                    <UsageDailyChart rows={report.rows as UsageDailySummary[]} />
                                ) : (
                                    <UsageBreakdownChart
                                        tab={report.tab}
                                        rows={report.rows}
                                    />
                                )}
                                <div className="usage-export-toolbar">
                                    <div>
                                        <strong>Экспорт результатов</strong>
                                        <small>
                                            {report.tab === 'summary' || report.tab === 'users'
                                                ? 'Только загруженная страница (до 50 строк).'
                                                : 'Все строки текущего ограниченного отчета.'}
                                            {report.filtersDirty && ' Не применяется измененный черновик фильтров.'}
                                        </small>
                                    </div>
                                    <button type="button" className="secondary-button" onClick={() => {
                                        try {
                                            downloadUsageCsv(report.tab, report.rows)
                                            setExportError('')
                                        } catch {
                                            setExportError('Не удалось создать CSV. Попробуйте повторно.')
                                        }
                                    }}>
                                        Скачать CSV
                                    </button>
                                </div>
                                {exportError && <p role="alert" className="usage-export-error">{exportError}</p>}
                        </div>
                        <UsageRows
                            tab={report.tab}
                            rows={report.rows}
                        />
                    </div>
                ) : null}
            </ResizableScrollRegion>
        </div>
    )
}
