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
                        <tr key={event.id}>
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

/*
 * Экспортирует ТОЛЬКО события,
 * уже загруженные на текущей странице.
 *
 * Это намеренно не "экспорт всех результатов".
 */
export function downloadAuditCsv(
    events: AuditEvent[],
): void {
    const rows: string[][] = [
        [
            'Дата и время',
            'Целевая организация',
            'ID организации',
            'Инициатор',
            'ID инициатора',
            'Тип события',
            'Код события',
            'ID события',
        ],

        ...events.map(
            (event) => [
                formatDateTime(
                    event.createdAt,
                ),

                event.targetOrganizationName
                    ?? '',

                event.targetOrganizationId,

                event.actorEmail
                    ?? event.actorDisplayName
                    ?? 'Система/исторический инициатор',

                event.actorUserId
                    ?? '',

                getAuditEventTypeLabel(
                    event.eventType,
                ),

                event.eventType,

                event.id,
            ],
        ),
    ]

    const csv =
        rows
            .map(
                (row) =>
                    row
                        .map(
                            escapeCsvCell,
                        )
                        .join(';'),
            )
            .join('\r\n')

    const blob =
        new Blob(
            [
                `\uFEFF${csv}`,
            ],
            {
                type:
                    'text/csv;charset=utf-8',
            },
        )

    const objectUrl =
        URL.createObjectURL(
            blob,
        )

    const link =
        document.createElement(
            'a',
        )

    link.href =
        objectUrl

    link.download =
        'safeai-audit-current-page.csv'

    document.body.appendChild(
        link,
    )

    link.click()
    link.remove()

    window.setTimeout(
        () =>
            URL.revokeObjectURL(
                objectUrl,
            ),
        1_000,
    )
}

function escapeCsvCell(
    value: string,
): string {
    return `"${value.replaceAll(
        '"',
        '""',
    )}"`
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

function getEventToneClass(
    eventType: string,
): string {
    if (
        eventType.includes(
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