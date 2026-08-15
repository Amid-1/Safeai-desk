// ============================================================
// frontend/src/components/admin/audit/AuditTable.tsx
// ============================================================

import type {
    AuditEvent,
    AuditTargetOrganizationDirectoryItem,
} from '../../../api/adminApi'
import {
    getAuditEventTypeLabel,
} from '../../../constants/auditEvents'
import {
    formatDateTime,
} from '../../../utils/format'
import AuditActor from './AuditActor'
import AuditPagination
    from './AuditPagination'

type AuditTableProps = {
    events: AuditEvent[]
    organizations:
        AuditTargetOrganizationDirectoryItem[]

    page: number
    totalPages: number
    totalElements: number
    loading: boolean

    onOpenDetails:
        (event: AuditEvent) => void
    onPageChange:
        (page: number) => void
}

function AuditTable({
    events,
    organizations,

    page,
    totalPages,
    totalElements,
    loading,

    onOpenDetails,
    onPageChange,
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
        <div className="card table-card audit-table-card">
            <div className="admin-table-wrapper">
                <table
                    className={
                        'admin-table audit-table'
                    }
                >
                    <thead>
                        <tr>
                            <th>Дата и время</th>
                            <th>
                                Целевая организация
                            </th>
                            <th>Инициатор</th>
                            <th>Тип события</th>
                            <th>Детали</th>
                        </tr>
                    </thead>

                    <tbody>
                        {events.map(
                            (event) => (
                                <tr key={event.id}>
                                    <td
                                        className={
                                            'audit-time-cell'
                                        }
                                    >
                                        {
                                            formatDateTime(
                                                event.createdAt,
                                            )
                                        }
                                    </td>

                                    <td>
                                        <OrganizationCell
                                            event={event}
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
                                            event={event}
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
            </div>

            <AuditPagination
                page={page}
                totalPages={totalPages}
                totalElements={
                    totalElements
                }
                loading={loading}
                onPageChange={
                    onPageChange
                }
            />
        </div>
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
                {event.targetOrganizationId}
            </span>
        </div>
    )
}

export default AuditTable

function getEventToneClass(eventType: string): string {
    if (eventType.includes('FAILED')
        || eventType.includes('EXCEEDED')
        || eventType.includes('SECURITY_')
        || eventType.includes('DELETED')) {
        return 'event-type-badge--danger'
    }

    if (eventType.includes('LOGIN_SUCCESS')
        || eventType.includes('CREATED')
        || eventType.includes('ADDED')) {
        return 'event-type-badge--success'
    }

    if (eventType.startsWith('KNOWLEDGE_')) {
        return 'event-type-badge--knowledge'
    }

    if (eventType.startsWith('AI_')
        || eventType.startsWith('CHAT_')) {
        return 'event-type-badge--ai'
    }

    return 'event-type-badge--neutral'
}
