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
import { useLayoutEffect, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent, KeyboardEvent as ReactKeyboardEvent } from 'react'
import type { UsageDailySummary } from '../api/usageApi'
import { UsageKpis } from '../components/admin/usage/UsageKpis'
import { UsageDailyChart } from '../components/admin/usage/UsageDailyChart'
import { UsageBreakdownChart } from '../components/admin/usage/UsageBreakdownChart'
import { downloadUsageCsv } from '../components/admin/usage/usageCsv'
import { autoFitUsageHeight } from './usagePanelHeight'
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
    // The normal view is one continuous page: no clipped table rows.
    // A focused, resizable split is entered only on explicit user request.
    const [focusedResults, setFocusedResults] = useState(false)
    const pageRef = useRef<HTMLDivElement | null>(null)
    const [showLowerRequest, setShowLowerRequest] = useState(0)
    const [showUpperRequest, setShowUpperRequest] = useState(0)
    const resultsRef = useRef<HTMLDivElement | null>(null)

    useLayoutEffect(() => {
        if (pageRef.current) pageRef.current.scrollTop = 0
    }, [focusedResults])
    const [naturalResultHeight, setNaturalResultHeight] = useState<number | null>(null)

    // In ordinary document flow the divider is visible and draggable.
    // Enter the bounded split BEFORE ResizableScrollRegion starts handling
    // the pointer. Its native pointer capture, movement and keyboard
    // implementation remain authoritative; no parallel drag system.
    const onWorkspacePointerDownCapture = (
        event: ReactPointerEvent<HTMLDivElement>,
    ) => {
        if (focusedResults || !showReportRows || event.button !== 0) return
        const target = event.target
        if (!(target instanceof Element)) return
        if (!target.closest('.resizable-scroll-region__handle')) return
        setFocusedResults(true)
    }

    const onWorkspaceKeyDownCapture = (
        event: ReactKeyboardEvent<HTMLDivElement>,
    ) => {
        if (focusedResults || !showReportRows) return
        if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return
        const target = event.target
        if (!(target instanceof Element)) return
        if (!target.closest('.resizable-scroll-region__handle')) return
        setFocusedResults(true)
    }

    const showReportRows =
        !report.loading
        && !report.error
        && report.rows.length > 0

    const showEmptyReport =
        !report.loading
        && !report.error
        && report.rows.length === 0

    // Measure actual KPI/chart/export/table content instead of estimating
    // required space from the number of rows. Keep the user-adjusted height
    // authoritative: ResizableScrollRegion applies autoFit only until the
    // first manual resize and restores it on double click.
    useLayoutEffect(() => {
        const content = resultsRef.current
        if (!showReportRows || !content) {
            setNaturalResultHeight(null)
            return
        }

        const measure = () => {
            const footer = content.closest('.resizable-scroll-region')
                ?.querySelector<HTMLElement>('.resizable-scroll-region__footer')
            const desired = Math.ceil(
                Math.max(content.scrollHeight, content.getBoundingClientRect().height)
                + (footer?.getBoundingClientRect().height ?? 45)
                + 32, // divider, viewport padding and a small breathing room
            )
            setNaturalResultHeight(old => old === desired ? old : desired)
        }

        measure()
        if (typeof ResizeObserver === 'undefined') {
            window.addEventListener('resize', measure)
            return () => window.removeEventListener('resize', measure)
        }
        const observer = new ResizeObserver(measure)
        observer.observe(content)
        const footer = content.closest('.resizable-scroll-region')
            ?.querySelector<HTMLElement>('.resizable-scroll-region__footer')
        if (footer) observer.observe(footer)
        return () => observer.disconnect()
    }, [showReportRows, report.tab, report.rows.length])

    // The automatic initial layout remains compact on laptop screens;
    // the expand action and drag handle can still use the whole viewport.
    const fittedResultsHeight = showReportRows
        ? autoFitUsageHeight(naturalResultHeight, report.tab)
        : 200

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
                        onExpandResults={() => {
                            setFocusedResults(true)
                            setShowLowerRequest(value => value + 1)
                        }}
                        onRestoreFilters={() => {
                            setFocusedResults(false)
                            setShowUpperRequest(value => value + 1)
                        }}
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

export default AdminUsagePage
