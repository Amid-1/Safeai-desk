// frontend/src/features/audit/ui/auditFilters/AuditOrganizationFields.tsx
// ============================================================
// frontend/src/features/audit/ui/auditFilters/AuditOrganizationFields.tsx
// ============================================================
import type { AuditFiltersProps } from './auditFilterTypes'
import { buildVisibleOrganizations } from './auditDirectoryOptions'
import { SearchableSelect } from '../../../../shared/ui/SearchableSelect'

type Props = Pick<
    AuditFiltersProps,
    'draftFilter' | 'organizations' | 'loading' | 'directoriesLoading' | 'organizationsError' | 'onFilterChange'
> & {
    organizationQuery: string
    onOrganizationQueryChange: (value: string) => void
}

export function AuditOrganizationFields({
    draftFilter,
    organizations,
    loading,
    directoriesLoading,
    organizationsError,
    onFilterChange,
    organizationQuery,
    onOrganizationQueryChange,
}: Props) {
    const visibleOrganizations = buildVisibleOrganizations(organizations, draftFilter.targetOrganizationId)
    return (
        <div className="audit-filter-field audit-filter-field--organization">
            <SearchableSelect id="audit-organization" label="Организация"
                value={draftFilter.targetOrganizationId}
                options={[{ value: '', label: 'Все организации' }, ...visibleOrganizations.map(org => ({
                    value: org.targetOrganizationId,
                    label: org.targetOrganizationName || 'Название недоступно',
                }))]}
                placeholder="Все организации" searchPlaceholder="Название или UUID"
                emptyMessage="Организации не найдены" disabled={loading}
                loading={directoriesLoading} error={organizationsError} filterLocally={false}
                helperText="Организация, которой касается событие. Поиск выполняется на сервере."
                onQueryChange={next => { if (next !== organizationQuery) onOrganizationQueryChange(next) }}
                onChange={targetOrganizationId => onFilterChange(current => ({...current, targetOrganizationId}))} />
        </div>
    )
}
