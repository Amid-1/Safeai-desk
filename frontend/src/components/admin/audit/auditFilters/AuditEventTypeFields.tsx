// ============================================================
// frontend/src/components/admin/audit/auditFilters/AuditEventTypeFields.tsx
// ============================================================
import { getAuditEventTypeLabel } from '../../../../constants/auditEvents'
import { groupAuditEventTypes } from '../auditEventGroups'
import type { AuditFiltersProps } from './auditFilterTypes'

type Props = Pick<
    AuditFiltersProps,
    'draftFilter' | 'eventTypes' | 'loading' | 'onFilterChange'
> & {
    eventTypeSearch: string
    onEventTypeSearchChange: (value: string) => void
}

export function AuditEventTypeFields({
    draftFilter,
    eventTypes,
    loading,
    onFilterChange,
    eventTypeSearch,
    onEventTypeSearchChange,
}: Props) {
    const groupedEventTypes = groupAuditEventTypes(eventTypes, eventTypeSearch, draftFilter.eventType)
    return (
                <div className="audit-filter-field audit-filter-field--event-type">
                    <label htmlFor="audit-event-type-search">
                        Поиск типа события
                    </label>
                    <input
                        id="audit-event-type-search"
                        type="search"
                        value={eventTypeSearch}
                        maxLength={128}
                        autoComplete="off"
                        disabled={loading}
                        placeholder="Название или технический код"
                        onChange={(event) => onEventTypeSearchChange(event.target.value)}
                    />
                    <label htmlFor="audit-event-type-select">
                        Тип события
                    </label>
                    <select
                        id="audit-event-type-select"
                        value={
                            draftFilter.eventType
                        }
                        disabled={loading}
                        onChange={(event) => {
                            const eventType =
                                event.target.value

                            onFilterChange(
                                (current) => ({
                                    ...current,
                                    eventType,
                                }),
                            )
                        }}
                    >
                        <option value="">
                            Все события
                        </option>

                        {groupedEventTypes.map(({ group, types }) => (
                            <optgroup key={group} label={group}>
                                {types.map((eventType) => (
                                    <option key={eventType} value={eventType}>
                                        {getAuditEventTypeLabel(eventType)}
                                        {' — '}{eventType}
                                    </option>
                                ))}
                            </optgroup>
                        ))}
                    </select>
                    {eventTypeSearch && groupedEventTypes.length === 0 && (
                        <small className="muted" role="status">
                            Совпадений нет. Очистите строку поиска.
                        </small>
                    )}
                </div>

    )
}
