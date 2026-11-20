// frontend/src/features/audit/ui/AdminAuditPageView.tsx
// ============================================================
// frontend/src/features/audit/ui/AdminAuditPageView.tsx
// Представление журнала аудита: рабочая область, таблица, CSV, детали.
// ============================================================
import { useEffect, useRef, useState } from 'react'
import { EmptyState, ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import ResizableScrollRegion from '../../../shared/ui/ResizableScrollRegion'
import { AdminPageHeader } from '../../../shared/ui/AdminPageHeader'
import AuditDetailsModal from './AuditDetailsModal'
import AuditFilters from './AuditFilters'
import AuditPagination from './AuditPagination'
import AuditTable from './AuditTable'
import AuditSummaryPanel from './AuditSummaryPanel'
import { downloadAuditCsv } from './auditCsv'
import type { AdminAuditController } from '../controller/useAdminAuditController'
import { getAuditEventTypeLabel } from '../constants/auditEvents'
import type { AuditCategory, AuditMetric } from '../api/adminAuditApi'

export type AdminAuditPageViewProps = {
    controller: AdminAuditController
}

export function AdminAuditPageView({ controller }: AdminAuditPageViewProps) {
    const {
        superAdmin,
        selectedEvent,
        draftFilter,
        eventTypes,
        organizations,
        actors,
        directoriesLoading,
        eventTypesError,
        organizationsError,
        actorsError,
        filterError,
        filtersDirty,
        effectiveLoading,
        events,
        loadError,
        pageResponse,
        selectedOrganizationFallback,
        showTable,
        searchActors,
        searchOrganizations,
        setDraftFilter,
        setSelectedEvent,
        applyDatePreset,
        applyFilters,
        filterByActor,
        resetFilters,
        retryLoad,
        changePage,
    } = controller
    const [compact, setCompact] = useState(false)
    const [lowerRequest, setLowerRequest] = useState(0)
    const [upperRequest, setUpperRequest] = useState(0)
    const upperRef = useRef<HTMLDivElement>(null)
    const hadTable = useRef(showTable)

    // При возвращении из пустого результата новая область тоже раскрывается.
    useEffect(() => {
        if (showTable && !hadTable.current && compact) {
            setLowerRequest(value => value + 1)
        }
        hadTable.current = showTable
    }, [showTable, compact])

    function revealResults() {
        setCompact(true)
        setLowerRequest(value => value + 1)
        if (upperRef.current) upperRef.current.scrollTop = 0
        const pane = upperRef.current?.closest('.resizable-scroll-region__upper')
        if (pane) pane.scrollTop = 0
    }

    function selectMetric(metric?: AuditMetric) {
        controller.selectAuditMetric(metric)
        revealResults()
    }

    function selectCategory(category?: AuditCategory) {
        controller.selectAuditCategory(category)
        revealResults()
    }

    function toggleFilters() {
        if (compact) {
            setCompact(false)
            setUpperRequest(value => value + 1)
        } else {
            revealResults()
        }
    }

    const activeFilters = [
        draftFilter.correlationId && 'Correlation ID: ' + draftFilter.correlationId,
        draftFilter.eventType && getAuditEventTypeLabel(draftFilter.eventType),
        superAdmin && draftFilter.targetOrganizationId && (
            organizations.find(org => org.targetOrganizationId === draftFilter.targetOrganizationId)?.targetOrganizationName
            || draftFilter.targetOrganizationId),
        draftFilter.actorUserId && (
            actors.find(actor => actor.actorUserId === draftFilter.actorUserId)?.actorEmail || draftFilter.actorUserId),
        draftFilter.actorEmail && 'История email: ' + draftFilter.actorEmail,
    ].filter(Boolean).join(' · ')
    const period = [draftFilter.dateFrom, draftFilter.dateTo]
        .map(date => date.split('-').reverse().join('.')).join(' — ')

    const upper = (
                    <div className="audit-page__upper" ref={upperRef}>
                        <AdminPageHeader className="audit-page__header page-hero page-hero--audit">
                            <div className="audit-page__heading">
                                <span className="audit-page__eyebrow">
                                    Безопасность и контроль
                                </span>

                                <h1>
                                    Журнал событий
                                </h1>

                                <p className="muted audit-page__intro">
                                    История действий пользователей и изменений
                                    в организации.
                                </p>
                            </div>

                            <div className="audit-page__timezone">
                                <span aria-hidden="true">
                                    ◷
                                </span>

                                {' '}
                                Время показано в вашем часовом поясе
                            </div>
                        </AdminPageHeader>

                        <AuditSummaryPanel summary={controller.summary} loading={controller.summaryLoading}
                            error={controller.summaryError} disabled={effectiveLoading || filtersDirty}
                            category={draftFilter.category} metric={draftFilter.metric}
                            onMetric={selectMetric} onCategory={selectCategory}
                            onRetry={retryLoad} />

                        <div className="audit-workspace-switch"
                            data-resizable-upper-boundary={compact && showTable ? undefined : ''}>
                            <button type="button" className="secondary-button"
                                aria-expanded={!compact} aria-controls="audit-workspace-filters"
                                disabled={!compact && filtersDirty}
                                onClick={toggleFilters}>
                                {compact ? 'Показать фильтры' : 'Свернуть фильтры'}
                            </button>
                            <span className="muted">
                                {compact ? <>Период: {period}{activeFilters && <> · {activeFilters}</>}</>
                                    : 'Карточки и категории открывают результаты; высоту можно менять вручную.'}
                                {filtersDirty && ' · Есть неприменённые изменения'}
                            </span>
                        </div>
                        <div id="audit-workspace-filters" hidden={compact}>
                        <AuditFilters
                            draftFilter={
                                draftFilter
                            }
                            eventTypes={
                                eventTypes
                            }
                            organizations={
                                organizations
                            }
                            actors={
                                actors
                            }
                            superAdmin={
                                superAdmin
                            }
                            loading={
                                effectiveLoading
                            }
                            directoriesLoading={
                                directoriesLoading
                            }
                            eventTypesError={
                                eventTypesError
                            }
                            organizationsError={
                                organizationsError
                            }
                            actorsError={
                                actorsError
                            }
                            filterError={
                                filterError
                            }
                            filtersDirty={
                                filtersDirty
                            }
                            onFilterChange={
                                setDraftFilter
                            }
                            onActorSearch={
                                searchActors
                            }
                            onOrganizationSearch={
                                searchOrganizations
                            }
                            onDatePreset={
                                applyDatePreset
                            }
                            onApply={
                                applyFilters
                            }
                            onReset={
                                resetFilters
                            }
                        />
                        </div>

                        {effectiveLoading
                            && events.length === 0
                            && (
                                <LoadingState
                                    message="Загрузка событий аудита..."
                                />
                            )}

                        {!effectiveLoading
                            && loadError
                            && events.length === 0
                            && (
                                <ErrorState
                                    title="Ошибка загрузки"
                                    message={
                                        loadError
                                    }
                                    action={
                                        <button
                                            type="button"
                                            onClick={
                                                retryLoad
                                            }
                                        >
                                            Повторить
                                        </button>
                                    }
                                />
                            )}

                        {!effectiveLoading
                            && !loadError
                            && events.length === 0
                            && (
                                <EmptyState
                                    title="Событий не найдено"
                                    message="Попробуйте изменить период или убрать часть фильтров."
                                />
                            )}

                        {showTable
                            && (
                                <div className="audit-table-card__toolbar">
                                    <div className="audit-results-summary">
                                        <h2>Результаты поиска <span>{pageResponse.totalElements.toLocaleString('ru-RU')}</span></h2>
                                        <p>Нажмите на имя для фильтрации событий или откройте карточку пользователя.
                                            {' '}CSV страницы содержит только её события.
                                            {effectiveLoading && <span role="status"> Обновляем результаты…</span>}
                                            {filtersDirty && (
                                                <span className="audit-csv-unapplied">
                                                    {' '}Не примененные изменения фильтров в файл не входят.
                                                </span>
                                            )}
                                        </p>
                                    </div>

                                    <div className="audit-export-actions">
                                    <button
                                        type="button"
                                        className="secondary-button"
                                        disabled={
                                            effectiveLoading
                                        }
                                        onClick={() =>
                                            downloadAuditCsv(
                                                events,
                                            )
                                        }
                                    >
                                        Скачать CSV текущей страницы
                                    </button>
                                    <button type="button" className="secondary-button"
                                        disabled={effectiveLoading || filtersDirty || !!loadError || controller.exportPending}
                                        onClick={controller.exportFiltered}>CSV по фильтрам (до 100)</button>
                                    {controller.filteredExportError && <p role="alert">{controller.filteredExportError}</p>}
                                    </div>
                                </div>
                            )}
                        <span className="resizable-scroll-region__upper-boundary-marker"
                            data-resizable-upper-boundary={compact && showTable ? '' : undefined}
                            data-resizable-upper-preferred-boundary aria-hidden="true" />
                    </div>
    )

    return (
        <div className="page audit-page">
            {showTable ? <ResizableScrollRegion
                storageKey="safeai:audit-events-height:v4"
                preserveUpperContent
                reserveLowerHeight
                collapseToFooter
                showLowerRequest={lowerRequest}
                showUpperRequest={upperRequest}
                viewportResetKey={`${pageResponse.page}:${events.map(event => event.id).join(',')}`}
                label="журнал событий"
                upper={upper}
                footer={
                    showTable
                        ? (
                            <AuditPagination
                                page={
                                    pageResponse.page
                                }
                                totalPages={
                                    pageResponse.totalPages
                                }
                                totalElements={
                                    pageResponse.totalElements
                                }
                                loading={
                                    effectiveLoading
                                }
                                onPageChange={
                                    changePage
                                }
                            />
                        )
                        : null
                }
                lowerClassName="card table-card audit-table-card"
                viewportClassName="admin-table-wrapper"
                defaultHeight={1600}
                autoFitHeight={1600}
                minHeight={280}
                maxHeight={1600}
                minUpperHeight={112}
            >
                {showTable
                    && (
                        <AuditTable
                            superAdmin={superAdmin}
                            events={
                                events
                            }
                            organizations={
                                organizations
                            }
                            onOpenDetails={
                                setSelectedEvent
                            }
                            onFilterActor={filterByActor}
                            actorFilterDisabled={effectiveLoading || filtersDirty}
                        />
                    )}
            </ResizableScrollRegion> : upper}

            {selectedEvent
                && (
                    <AuditDetailsModal
                        event={
                            selectedEvent
                        }
                        organizationFallbackName={
                            selectedOrganizationFallback
                        }
                        onClose={() =>
                            setSelectedEvent(
                                null,
                            )
                        }
                    />
                )}
        </div>
    )
}
