// ============================================================
// frontend/src/components/admin/audit/AuditTable.tsx
// ============================================================
import type {
    AuditEvent,
} from '../../../api/adminApi'

import {
    getAuditEventTypeLabel,
} from '../../../constants/auditEvents'

import {
    formatDateTime,
} from '../../../utils/format'

import AuditActor from './AuditActor'
import AuditPagination from './AuditPagination'

type AuditTableProps = {
    events: AuditEvent[]
    organizationNameById: Map<string, string>
    page: number
    totalPages: number
    loading: boolean
    onOpenDetails: (event: AuditEvent) => void
    onPageChange: (page: number) => void
}

function AuditTable({
    events,
    organizationNameById,
    page,
    totalPages,
    loading,
    onOpenDetails,
    onPageChange,
}: AuditTableProps) {
    return (
        <div className="card table-card">
            <div className="admin-table-wrapper">
                <table
                    className="admin-table audit-table"
                    aria-busy={loading}
                >
                    <thead>
                        <tr>
                            <th scope="col">
                                Дата и время
                            </th>

                            <th scope="col">
                                Целевая организация
                            </th>

                            <th scope="col">
                                Инициатор
                            </th>

                            <th scope="col">
                                Тип события
                            </th>

                            <th scope="col">
                                Детали
                            </th>
                        </tr>
                    </thead>

                    <tbody>
                        {events.map((event) => {
                            const eventLabel =
                                getAuditEventTypeLabel(
                                    event.eventType,
                                )

                            return (
                                <tr key={event.id}>
                                    <td>
                                        {formatDateTime(
                                            event.createdAt,
                                        )}
                                    </td>

                                    <td>
                                        <OrganizationCell
                                            organizationId={
                                                event
                                                    .targetOrganizationId
                                            }
                                            organizationSnapshotName={
                                                event
                                                    .targetOrganizationName
                                            }
                                            organizationNameById={
                                                organizationNameById
                                            }
                                        />
                                    </td>

                                    <td>
                                        <AuditActor
                                            event={event}
                                        />
                                    </td>

                                    <td>
                                        <span className="event-type-badge">
                                            {eventLabel}
                                        </span>
                                    </td>

                                    <td>
                                        {hasDetails(event) ? (
                                            <button
                                                type="button"
                                                className="secondary-button"
                                                aria-label={
                                                    `Показать детали события «${eventLabel}»`
                                                }
                                                onClick={() => {
                                                    onOpenDetails(
                                                        event,
                                                    )
                                                }}
                                            >
                                                Показать детали
                                            </button>
                                        ) : (
                                            <span className="muted">
                                                —
                                            </span>
                                        )}
                                    </td>
                                </tr>
                            )
                        })}
                    </tbody>
                </table>
            </div>

            <AuditPagination
                page={page}
                totalPages={totalPages}
                loading={loading}
                onPageChange={onPageChange}
            />
        </div>
    )
}

type OrganizationCellProps = {
    organizationId: string
    organizationSnapshotName: string | null
    organizationNameById: Map<string, string>
}

function OrganizationCell({
    organizationId,
    organizationSnapshotName,
    organizationNameById,
}: OrganizationCellProps) {
    /*
     * Для исторического аудита snapshot имеет приоритет:
     * текущее название организации могло измениться после события.
     */
    const snapshotName =
        normalizeOptionalText(
            organizationSnapshotName,
        )

    const currentName =
        normalizeOptionalText(
            organizationNameById.get(
                organizationId,
            ) ?? null,
        )

    const organizationName =
        snapshotName ?? currentName

    if (!organizationName) {
        return (
            <span
                className="muted"
                title={organizationId}
            >
                <code>
                    {organizationId}
                </code>
            </span>
        )
    }

    return (
        <div className="audit-organization">
            <span title={organizationId}>
                {organizationName}
            </span>

            <span className="muted">
                <code>
                    {organizationId}
                </code>
            </span>
        </div>
    )
}

function hasDetails(
    event: AuditEvent,
): boolean {
    return (
        Object.keys(event.details).length > 0
        || event.detailsTruncated
        || event.detailsInvalid
    )
}

function normalizeOptionalText(
    value: string | null,
): string | null {
    if (value === null) {
        return null
    }

    const normalized =
        value.trim()

    return normalized || null
}

export default AuditTable