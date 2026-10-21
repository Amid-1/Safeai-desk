// frontend/src/features/audit/ui/auditFilters/AuditOrganizationFields.tsx
// ============================================================
// frontend/src/features/audit/ui/auditFilters/AuditOrganizationFields.tsx
// ============================================================
import type { AuditFiltersProps } from './auditFilterTypes'
import { buildVisibleOrganizations, formatOrganizationOption } from './auditDirectoryOptions'
import { DirectoryWarning } from './auditFilterControls'

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
                    <>
                        <label className="audit-filter-field audit-filter-field--organization-search">
                            Поиск целевой
                            организации

                            <input
                                type="search"
                                value={
                                    organizationQuery
                                }
                                maxLength={255}
                                disabled={loading}
                                autoComplete="off"
                                placeholder={
                                    'Название или UUID'
                                }
                                onChange={(event) =>
                                    onOrganizationQueryChange(
                                        event.target.value,
                                    )
                                }
                            />

                            <small className="muted">
                                Поиск обновляет список.
                                Фильтр применяется после
                                выбора организации.
                            </small>
                        </label>

                        <label className="audit-filter-field audit-filter-field--organization">
                            Целевая организация

                            <select
                                value={
                                    draftFilter
                                        .targetOrganizationId
                                }
                                disabled={
                                    loading
                                    || directoriesLoading
                                }
                                onChange={(event) => {
                                    const targetOrganizationId =
                                        event.target.value

                                    onFilterChange(
                                        (current) => ({
                                            ...current,
                                            targetOrganizationId,
                                        }),
                                    )
                                }}
                            >
                                <option value="">
                                    Все целевые организации
                                </option>

                                {visibleOrganizations.map(
                                    (organization) => (
                                        <option
                                            key={
                                                organization
                                                    .targetOrganizationId
                                            }
                                            value={
                                                organization
                                                    .targetOrganizationId
                                            }
                                        >
                                            {
                                                formatOrganizationOption(
                                                    organization,
                                                )
                                            }
                                        </option>
                                    ),
                                )}
                            </select>
                        </label>

                        <DirectoryWarning
                            message={
                                organizationsError
                            }
                        />
                    </>
    )
}
