// frontend/src/pages/AdminUsagePage.tsx

import PageErrorBoundary
    from '../components/PageErrorBoundary'
import ResizableScrollRegion
    from '../components/ResizableScrollRegion'
import {
    AdminUsageControls,
} from '../components/admin/usage/AdminUsageControls'
import {
    UsageReportFooter,
    UsageRows,
} from '../components/admin/usage/UsageReportView'
import {
    useAdminUsageReport,
} from './useAdminUsageReport'
import { useState } from 'react'
import type { UsageDailySummary } from '../api/usageApi'
import { UsageKpis } from '../components/admin/usage/UsageKpis'
import { UsageDailyChart } from '../components/admin/usage/UsageDailyChart'
import { downloadUsageCsv } from '../components/admin/usage/usageCsv'
import './AdminUsagePage.css'

function AdminUsagePage() {
    return (
        <PageErrorBoundary>
            <AdminUsagePageContent />
        </PageErrorBoundary>
    )
}

function AdminUsagePageContent() {
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

    return (
        <div className="page usage-page">
            <ResizableScrollRegion
                storageKey="safeai:usage-report-height:v3"
                label="таблица использования"
                preserveUpperContent
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
                    />
                )}
                autoFitHeight={showReportRows
                    ? Math.min(710, 265 + report.rows.length * 75
                        + (report.tab === 'daily' ? 350 : 0))
                    : 100}
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
                defaultHeight={430}
                minHeight={72}
                maxHeight={760}
                minUpperHeight={112}
            >
                {showReportRows ? (
                    <div className="usage-report-results">
                        <div className="usage-report-insights">

                                <UsageKpis rows={report.rows} tab={report.tab} />
                                {report.tab === 'daily' && (
                                    <UsageDailyChart rows={report.rows as UsageDailySummary[]} />
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

export default AdminUsagePage
