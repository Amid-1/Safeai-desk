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

export default function AuditFilters({
    draftFilter, eventTypes, organizations, actors,
    superAdmin, loading, directoriesLoading,
    eventTypesError, organizationsError, actorsError, filterError, filtersDirty,
    onFilterChange, onActorSearch, onOrganizationSearch,
    onDatePreset, onApply, onReset,
}: AuditFiltersProps) {
    const [eventTypeSearch, setEventTypeSearch] = useState('')
    const [activePreset, setActivePreset] = useState<DatePreset | null>(null)
    const {
        organizationQuery, actorQuery, scheduleOrganizationSearch, scheduleActorSearch,
    } = useAuditDirectoryQueries(onActorSearch, onOrganizationSearch)

    return (
        <section className="card form-card audit-filters" aria-labelledby="audit-filters-title">
            <h2 id="audit-filters-title">Найти события</h2>
            <p className="muted audit-filters__subtitle">
                Уточните тип события, участника или период.
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
                        loading={loading} directoriesLoading={directoriesLoading}
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
                <AuditSubmitActions
                    directoriesLoading={directoriesLoading} filterError={filterError}
                    filtersDirty={filtersDirty} loading={loading}
                    onApply={onApply} onReset={onReset}
                    setActivePreset={setActivePreset}
                    clearEventTypeSearch={() => setEventTypeSearch('')}
                />
            </div>
        </section>
    )
}
