// frontend/src/features/audit/ui/AuditTable.tsx
// ============================================================
// frontend/src/features/audit/ui/AuditTable.tsx
// ============================================================

import type {
    AuditEvent,
    AuditTargetOrganizationDirectoryItem,
} from '../api/adminAuditApi'
import {
    getAuditEventTypeLabel,
} from '../constants/auditEvents'
import {
    formatDateTime,
} from '../../../shared/lib/format'
import AuditActor from './AuditActor'

type AuditTableProps = {
    events: AuditEvent[]

    organizations:
        AuditTargetOrganizationDirectoryItem[]

    onOpenDetails:
        (event: AuditEvent) => void
}

function AuditTable({
    events,
    organizations,
    onOpenDetails,
}: AuditTableProps) {
    const organizationNameById =
        new Map(
            organizations.map(
                (organization) => [
                    organization
                        .targetOrganizationId,

                    organization
                        .targetOrganizationName,
                ],
            ),
        )

    return (
        <table
            className={
                'admin-table audit-table'
            }
        >
            <thead>
                <tr>
                    <th>
                        Дата и время
                    </th>

                    <th>
                        Целевая организация
                    </th>

                    <th>
                        Инициатор
                    </th>

                    <th>
                        Тип события
                    </th>

                    <th>
                        Детали
                    </th>
                </tr>
            </thead>

            <tbody>
                {events.map(
                    (event) => (
                        <tr key={event.id} className={getAuditRowClass(event.eventType)}>
                            <td>
                                {
                                    formatDateTime(
                                        event.createdAt,
                                    )
                                }
                            </td>

                            <td>
                                <OrganizationCell
                                    event={
                                        event
                                    }
                                    directoryName={
                                        organizationNameById.get(
                                            event.targetOrganizationId,
                                        )
                                        ?? null
                                    }
                                />
                            </td>

                            <td>
                                <AuditActor
                                    event={
                                        event
                                    }
                                />
                            </td>

                            <td>
                                <div
                                    className={
                                        'audit-event-type'
                                    }
                                >
                                    <span
                                        className={
                                            'event-type-badge '
                                            + getEventToneClass(
                                                event.eventType,
                                            )
                                        }
                                    >
                                        {
                                            getAuditEventTypeLabel(
                                                event.eventType,
                                            )
                                        }
                                    </span>

                                    <span
                                        className={
                                            'audit-event-code '
                                            + 'audit-monospace'
                                        }
                                    >
                                        {
                                            event.eventType
                                        }
                                    </span>
                                </div>
                            </td>

                            <td>
                                <div
                                    className={
                                        'audit-details-action'
                                    }
                                >
                                    {Object.keys(
                                        event.details,
                                    ).length === 0
                                    && !event.detailsInvalid
                                        ? (
                                            <span className="muted">
                                                —
                                            </span>
                                        )
                                        : (
                                            <button
                                                type="button"
                                                className={
                                                    'secondary-button'
                                                }
                                                onClick={() =>
                                                    onOpenDetails(
                                                        event,
                                                    )
                                                }
                                            >
                                                Подробнее
                                            </button>
                                        )}

                                    {event.detailsTruncated
                                        && (
                                            <span
                                                className={
                                                    'audit-details-flag'
                                                }
                                            >
                                                Данные ограничены
                                            </span>
                                        )}
                                </div>
                            </td>
                        </tr>
                    ),
                )}
            </tbody>
        </table>
    )
}

function OrganizationCell({
    event,
    directoryName,
}: {
    event: AuditEvent
    directoryName: string | null
}) {
    const name =
        event.targetOrganizationName
        ?? directoryName
        ?? 'Название недоступно'

    return (
        <div
            className={
                'audit-organization '
                + 'audit-identity'
            }
        >
            <strong
                className={
                    'audit-identity__primary'
                }
            >
                {name}
            </strong>

            <span
                className={
                    'audit-identity__meta '
                    + 'audit-monospace'
                }
                title={
                    event.targetOrganizationId
                }
            >
                ID:
                {' '}
                {
                    shortAuditIdentifier(
                        event.targetOrganizationId,
                    )
                }
            </span>
        </div>
    )
}

function shortAuditIdentifier(
    value: string,
): string {
    return value.length > 16
        ? `${value.slice(
            0,
            8,
        )}…${value.slice(-4)}`
        : value
}

/** Row accents complement, never replace, the human-readable label. */
function getAuditRowClass(eventType: string): string {
    if (isSecurityOrFailureEvent(eventType)) return 'audit-row--risk'
    if (isAdministrativeEvent(eventType)) return 'audit-row--administrative'
    return 'audit-row--information'
}

function isSecurityOrFailureEvent(type: string): boolean {
    return type.includes('FAILED') || type.includes('DENIED')
        || type.includes('EXCEEDED') || type.startsWith('SECURITY_')
        || type === 'USER_LOGIN_FAILED' || type.includes('DEAD_LETTER')
}

function isAdministrativeEvent(type: string): boolean {
    return (
        (type.startsWith('USER_') && ![
            'USER_LOGIN_SUCCESS', 'USER_LOGOUT', 'USER_LOGIN_FAILED',
        ].includes(type))
        || type.startsWith('ORGANIZATION_')
        || type.startsWith('KNOWLEDGE_BASE_MEMBER_')
        || type.startsWith('MODEL_POLICY_')
        || type.startsWith('MODEL_CATALOG_')
    )
}

function getEventToneClass(
    eventType: string,
): string {
    if (
        isSecurityOrFailureEvent(eventType)
        || eventType.includes(
            'FAILED',
        )
        || eventType.includes(
            'EXCEEDED',
        )
        || eventType.includes(
            'SECURITY_',
        )
        || eventType.includes(
            'DELETED',
        )
    ) {
        return (
            'event-type-badge--danger'
        )
    }

    if (isAdministrativeEvent(eventType)) {
        return 'event-type-badge--admin'
    }

    if (
        eventType.includes(
            'LOGIN_SUCCESS',
        )
        || eventType.includes(
            'CREATED',
        )
        || eventType.includes(
            'ADDED',
        )
    ) {
        return (
            'event-type-badge--success'
        )
    }

    if (
        eventType.startsWith(
            'KNOWLEDGE_',
        )
    ) {
        return (
            'event-type-badge--knowledge'
        )
    }

    if (
        eventType.startsWith(
            'AI_',
        )
        || eventType.startsWith(
            'CHAT_',
        )
    ) {
        return (
            'event-type-badge--ai'
        )
    }

    return (
        'event-type-badge--neutral'
    )
}

export default AuditTable
