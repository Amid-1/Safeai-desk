// frontend/src/features/audit/ui/AuditTable.tsx
import type { AuditEvent, AuditTargetOrganizationDirectoryItem } from '../api/adminAuditApi'
import { formatDateTime } from '../../../shared/lib/format'
import AuditActor from './AuditActor'
import { auditEventResult, auditEventTitle } from './auditEventPresentation'
import { getAuditEventGroup } from './auditEventGroups'
import type { AuditEventGroup } from './auditEventGroups'

export default function AuditTable({ events, organizations, superAdmin, onOpenDetails, onFilterActor, actorFilterDisabled }: {
    events: AuditEvent[]
    organizations: AuditTargetOrganizationDirectoryItem[]
    superAdmin: boolean
    onOpenDetails: (event: AuditEvent) => void
    onFilterActor?: (actorId: string) => void
    actorFilterDisabled?: boolean
}) {
    const organizationNames = new Map(organizations.map((item) =>
        [item.targetOrganizationId, item.targetOrganizationName]))
    return <table className={'admin-table audit-table ' + (superAdmin ? 'audit-table--global' : 'audit-table--tenant')}>
        <thead><tr>
            <th>Дата и время</th>
            {superAdmin && <th>Организация</th>}
            <th>Пользователь / система</th><th>Событие</th><th>Результат</th><th>Подробности</th>
        </tr></thead>
        <tbody>{events.map((event) => {
            const actorId = event.actorUserId
            const result = auditEventResult(event.eventType)
            const administrative = /^(USER_(CREATED|UPDATED|ENABLED_CHANGED|ROLES_CHANGED|PASSWORD_RESET|PERMANENTLY_DELETED)|ORGANIZATION_|KNOWLEDGE_BASE_MEMBER_|MODEL_(POLICY|CATALOG)_)/.test(event.eventType)
            const rowClass = result.tone === 'danger' ? 'audit-row--risk'
                : administrative ? 'audit-row--administrative' : 'audit-row--information'
            return <tr key={event.id} className={rowClass}>
                <td className="audit-time-cell">{formatDateTime(event.createdAt)}</td>
                {superAdmin && <td>{event.targetOrganizationId
                    ? <a className="admin-related-link" href={`/admin/organizations?organizationId=${encodeURIComponent(event.targetOrganizationId)}`}>
                        {event.targetOrganizationName ?? organizationNames.get(event.targetOrganizationId) ?? 'Открыть организацию'}
                    </a>
                    : <span>Название недоступно</span>}</td>}
                <td><div className="audit-actor-cell">
                    <span className="audit-actor-avatar" aria-hidden="true">{actorInitials(event)}</span>
                    <div className="audit-actor-related"><AuditActor event={event} hideIds
                        onFilterActor={onFilterActor && actorId ? () => onFilterActor(actorId) : undefined}
                        filterDisabled={actorFilterDisabled} />
                    {superAdmin && actorId && <a className="admin-related-link audit-related-action"
                        href={`/admin/users?userId=${encodeURIComponent(actorId)}`}>Карточка пользователя</a>}</div>
                </div></td>
                <td><div className="audit-event-cell"><CategoryIcon group={getAuditEventGroup(event.eventType)} />
                    <span>{auditEventTitle(event.eventType)}</span></div></td>
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

function actorInitials(event: AuditEvent): string {
    if (event.eventType === 'USER_LOGIN_FAILED' && !event.actorUserId) return '?'
    const identity = event.actorDisplayName?.trim() || event.actorEmail?.trim()
    return identity ? Array.from(identity).slice(0, 2).join('').toLocaleUpperCase('ru') : event.actorUserId ? '?' : 'С'
}

const categoryIcons: Record<AuditEventGroup, { tone: string; path: string }> = {
    'Авторизация и безопасность': { tone: 'security', path: 'M12 22s8-4 8-11V5l-8-3-8 3v6c0 7 8 11 8 11z M9 12l2 2 4-4' },
    'Пользователи и организации': { tone: 'people', path: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2 M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z' },
    'Базы знаний и документы': { tone: 'knowledge', path: 'M4 5h6a3 3 0 0 1 3 3v12a3 3 0 0 0-3-3H4z M13 8a3 3 0 0 1 3-3h4v12h-4a3 3 0 0 0-3 3' },
    'AI, чаты и модели': { tone: 'ai', path: 'M12 2l2.5 7.5L22 12l-7.5 2.5L12 22l-2.5-7.5L2 12l7.5-2.5z' },
    'Системные события': { tone: 'system', path: 'M3 7h18v10H3z M7 11h2 M7 14h2 M13 12h4 M12 17v4 M8 21h8' },
}

function CategoryIcon({ group }: { group: AuditEventGroup }) {
    const { tone, path } = categoryIcons[group]
    return <span className={'audit-category-icon audit-category-icon--' + tone} title={group}>
        <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor"
            strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={path} /></svg>
    </span>
}
