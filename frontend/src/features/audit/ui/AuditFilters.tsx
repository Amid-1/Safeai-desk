// frontend/src/features/audit/ui/AuditFilters.tsx
// ============================================================
// frontend/src/features/audit/ui/AuditFilters.tsx
// ============================================================
import { useState } from 'react'
import type { DatePreset } from './types'
import type { AuditFiltersProps } from './auditFilters/auditFilterTypes'
import { useAuditDirectoryQueries } from './auditFilters/useAuditDirectoryQueries'
import { AuditEventTypeFields } from './auditFilters/AuditEventTypeFields'
import { AuditOrganizationFields } from './auditFilters/AuditOrganizationFields'
import { AuditActorFields } from './auditFilters/AuditActorFields'
import { AuditDateFields } from './auditFilters/AuditDateFields'
import { AuditSubmitActions } from './auditFilters/AuditSubmitActions'
import { DirectoryWarning } from './auditFilters/auditFilterControls'
import { applyAuditDatePreset } from '../lib/adminAudit.helpers'

export default function AuditFilters({
    draftFilter, eventTypes, organizations, actors,
    superAdmin, loading, directoriesLoading,
    eventTypesError, organizationsError, actorsError, filterError, filtersDirty,
    onFilterChange, onActorSearch, onOrganizationSearch,
    onDatePreset, onApply, onReset,
}: AuditFiltersProps) {
    const [eventTypeSearch, setEventTypeSearch] = useState('')
    const [activePreset, setActivePreset] = useState<DatePreset | null>(() => {
        const presets: DatePreset[] = ['today', 'yesterday', 'last7Days', 'last30Days', 'last90Days', 'last365Days']
        return presets.find((preset) => {
            const range = applyAuditDatePreset(draftFilter, preset)
            return range.dateFrom === draftFilter.dateFrom && range.dateTo === draftFilter.dateTo
        }) ?? null
    })
    const {
        organizationQuery, organizationSearchScheduled, actorQuery, scheduleOrganizationSearch, scheduleActorSearch,
    } = useAuditDirectoryQueries(onActorSearch, onOrganizationSearch)

    return (
        <section className="card form-card audit-filters" aria-labelledby="audit-filters-title">
            <h2 id="audit-filters-title">Поиск событий</h2>
            <p className="muted audit-filters__subtitle">
                Выберите событие, пользователя или период. Поиск охватывает весь доступный журнал.
            </p>
            <div className="form">
                <AuditEventTypeFields
                    draftFilter={draftFilter} eventTypes={eventTypes}
                    loading={loading} onFilterChange={onFilterChange}
                    eventTypeSearch={eventTypeSearch}
                    onEventTypeSearchChange={setEventTypeSearch}
                />
                <DirectoryWarning message={eventTypesError} />
                {superAdmin && (
                    <AuditOrganizationFields
                        draftFilter={draftFilter} organizations={organizations}
                        loading={loading} directoriesLoading={directoriesLoading || organizationSearchScheduled}
                        organizationsError={organizationsError}
                        onFilterChange={onFilterChange}
                        organizationQuery={organizationQuery}
                        onOrganizationQueryChange={scheduleOrganizationSearch}
                    />
                )}
                <AuditActorFields
                    draftFilter={draftFilter} actors={actors}
                    loading={loading} directoriesLoading={directoriesLoading}
                    actorsError={actorsError} onFilterChange={onFilterChange}
                    actorQuery={actorQuery} onActorQueryChange={scheduleActorSearch}
                />
                <AuditDateFields
                    draftFilter={draftFilter} loading={loading}
                    onFilterChange={onFilterChange} onDatePreset={onDatePreset}
                    activePreset={activePreset} setActivePreset={setActivePreset}
                />
                <details className="audit-advanced-filters" open={draftFilter.actorEmail ? true : undefined}>
                    <summary>Дополнительные фильтры{draftFilter.actorEmail ? ' · активны' : ''}</summary>
                    <label>Поиск по email в истории
                        <input type="search" value={draftFilter.actorEmail} maxLength={320}
                            disabled={loading} placeholder="Например, admin@company.ru"
                            onChange={(event) => {
                                const actorEmail = event.target.value
                                onFilterChange((current) => ({ ...current, actorEmail }))
                            }} />
                        <small>Полный email или его начало. Учитывается адрес на момент события, даже если он изменился.</small>
                    </label>
                </details>
                <AuditSubmitActions
                    directoriesLoading={directoriesLoading} filterError={filterError}
                    filtersDirty={filtersDirty} loading={loading}
                    onApply={onApply} onReset={onReset}
                    setActivePreset={setActivePreset}
                    clearEventTypeSearch={() => {
                        setEventTypeSearch('')
                        scheduleActorSearch('')
                        scheduleOrganizationSearch('')
                    }}
                />
            </div>
        </section>
    )
}
