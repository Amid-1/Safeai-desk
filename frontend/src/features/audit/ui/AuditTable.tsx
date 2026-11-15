// frontend/src/features/audit/ui/AuditTable.tsx
import type { AuditEvent, AuditTargetOrganizationDirectoryItem } from '../api/adminAuditApi'
import { formatDateTime } from '../../../shared/lib/format'
import AuditActor from './AuditActor'
import { auditEventResult, auditEventTitle } from './auditEventPresentation'

export default function AuditTable({ events, organizations, superAdmin, onOpenDetails }: {
    events: AuditEvent[]
    organizations: AuditTargetOrganizationDirectoryItem[]
    superAdmin: boolean
    onOpenDetails: (event: AuditEvent) => void
}) {
    const organizationNames = new Map(organizations.map((item) =>
        [item.targetOrganizationId, item.targetOrganizationName]))
    return <table className={'admin-table audit-table ' + (superAdmin ? 'audit-table--global' : 'audit-table--tenant')}>
        <thead><tr>
            <th>Дата и время</th>
            {superAdmin && <th>Организация</th>}
            <th>Кто выполнил</th><th>Событие</th><th>Результат</th><th>Детали</th>
        </tr></thead>
        <tbody>{events.map((event) => {
            const result = auditEventResult(event.eventType)
            const administrative = /^(USER_(CREATED|UPDATED|ENABLED_CHANGED|ROLES_CHANGED|PASSWORD_RESET|PERMANENTLY_DELETED)|ORGANIZATION_|KNOWLEDGE_BASE_MEMBER_|MODEL_(POLICY|CATALOG)_)/.test(event.eventType)
            const rowClass = result.tone === 'danger' ? 'audit-row--risk'
                : administrative ? 'audit-row--administrative' : 'audit-row--information'
            return <tr key={event.id} className={rowClass}>
                <td>{formatDateTime(event.createdAt)}</td>
                {superAdmin && <td><strong>{event.targetOrganizationName
                    ?? organizationNames.get(event.targetOrganizationId) ?? 'Название недоступно'}</strong></td>}
                <td><AuditActor event={event} /></td>
                <td>{auditEventTitle(event.eventType)}</td>
                <td><span className={'event-type-badge event-type-badge--' + result.tone}>{result.label}</span></td>
                <td><div className="audit-details-action">
                    <button type="button" className="secondary-button" onClick={() => onOpenDetails(event)}>Подробнее</button>
                    {event.detailsInvalid && <span className="audit-details-flag">Подробности недоступны</span>}
                    {event.detailsTruncated && <span className="audit-details-flag">Данные ограничены</span>}
                </div></td>
            </tr>
        })}</tbody>
    </table>
}
