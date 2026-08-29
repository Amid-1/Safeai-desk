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
                storageKey="safeai:usage-report-height"
                label="таблица использования"
                upper={(
                    <AdminUsageControls
                        tab={report.tab}
                        isSuperAdmin={
                            report.isSuperAdmin
                        }
                        effectiveOrganizationId={
                            report.effectiveOrganizationId
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
                        filterError={
                            report.filterError
                        }
                        loading={report.loading}
                        error={report.error}
                        showEmptyReport={
                            showEmptyReport
                        }
                        onDateFromChange={
                            report.setDraftDateFrom
                        }
                        onDateToChange={
                            report.setDraftDateTo
                        }
                        onModelChange={
                            report.setDraftModel
                        }
                        onOrganizationIdChange={
                            report.setDraftOrganizationId
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
                {showReportRows
                    ? (
                        <UsageRows
                            tab={report.tab}
                            rows={report.rows}
                        />
                    )
                    : null}
            </ResizableScrollRegion>
        </div>
    )
}

export default AdminUsagePage
