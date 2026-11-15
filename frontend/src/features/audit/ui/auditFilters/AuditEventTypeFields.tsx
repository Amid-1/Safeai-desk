// frontend/src/features/audit/ui/auditFilters/AuditEventTypeFields.tsx
import { auditEventTitle } from '../auditEventPresentation'
import { groupAuditEventTypes } from '../auditEventGroups'
import { AuditSearchPicker } from './AuditSearchPicker'
import type { AuditFiltersProps } from './auditFilterTypes'

type Props = Pick<AuditFiltersProps, 'draftFilter' | 'eventTypes' | 'loading' | 'onFilterChange'> & {
    eventTypeSearch: string
    onEventTypeSearchChange: (value: string) => void
}
export function AuditEventTypeFields({ draftFilter, eventTypes, loading, onFilterChange,
    eventTypeSearch, onEventTypeSearchChange }: Props) {
    const options = groupAuditEventTypes(eventTypes, eventTypeSearch, draftFilter.eventType)
        .flatMap(({ group, types }) => types.map((value) => ({ value, group, label: auditEventTitle(value) })))
    return <AuditSearchPicker label="Событие" placeholder="Все события — найти по названию"
        allLabel="Все события" query={eventTypeSearch} onQueryChange={onEventTypeSearchChange}
        value={draftFilter.eventType} options={options} disabled={loading} maxLength={128}
        onSelect={(eventType) => onFilterChange((current) => ({ ...current, eventType }))} />
}
