// frontend/src/features/audit/ui/AdminAuditPageView.tsx
// ============================================================
// frontend/src/features/audit/ui/AdminAuditPageView.tsx
// Представление журнала аудита: рабочая область, таблица, CSV, детали.
// ============================================================
import { EmptyState, ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import ResizableScrollRegion from '../../../shared/ui/ResizableScrollRegion'
import { AdminPageHeader } from '../../../shared/ui/AdminPageHeader'
import AuditDetailsModal from './AuditDetailsModal'
import AuditFilters from './AuditFilters'
import AuditPagination from './AuditPagination'
import AuditTable from './AuditTable'
import { downloadAuditCsv } from './auditCsv'
import type { AdminAuditController } from '../controller/useAdminAuditController'

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

    const upper = (
                    <div className="audit-page__upper">
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
                        <span className="resizable-scroll-region__upper-boundary-marker" data-resizable-upper-preferred-boundary aria-hidden="true" />
                    </div>
    )

    return (
        <div className="page audit-page">
            {showTable ? <ResizableScrollRegion
                storageKey="safeai:audit-events-height:v4"
                preserveUpperContent
                reserveLowerHeight
                collapseToFooter
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
