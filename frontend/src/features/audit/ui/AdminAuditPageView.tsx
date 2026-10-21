// frontend/src/features/audit/ui/AdminAuditPageView.tsx
// ============================================================
// frontend/src/features/audit/ui/AdminAuditPageView.tsx
// Представление журнала аудита: рабочая область, таблица, CSV, детали.
// ============================================================
import { EmptyState, ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import ResizableScrollRegion from '../../../shared/ui/ResizableScrollRegion'
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
        resetFilters,
        retryLoad,
        changePage,
    } = controller

    return (
        <div className="page audit-page">
            <ResizableScrollRegion
                storageKey="safeai:audit-events-height"
                label="журнал событий"
                upper={
                    <div className="audit-page__upper">
                        <header className="audit-page__header page-hero page-hero--audit">
                            <div className="audit-page__heading">
                                <span className="audit-page__eyebrow">
                                    Безопасность и контроль
                                </span>

                                <h1>
                                    Аудит событий
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
                        </header>

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
                                    <p>
                                        Показана страница
                                        {' '}
                                        {
                                            pageResponse.page
                                            + 1
                                        }
                                        .
                                        {' '}
                                        CSV содержит только события этой страницы.
                                        {filtersDirty && (
                                            <span className="audit-csv-unapplied">
                                                {' '}Не примененные изменения фильтров в файл не входят.
                                            </span>
                                        )}
                                    </p>

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
                                </div>
                            )}
                    </div>
                }
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
                defaultHeight={430}
                minHeight={72}
                maxHeight={760}
                minUpperHeight={112}
            >
                {showTable
                    && (
                        <AuditTable
                            events={
                                events
                            }
                            organizations={
                                organizations
                            }
                            onOpenDetails={
                                setSelectedEvent
                            }
                        />
                    )}
            </ResizableScrollRegion>

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
