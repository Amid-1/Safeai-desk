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
                <table className="admin-table audit-table">
                    <thead>
                    <tr>
                        <th>Дата и время</th>
                        <th>Организация</th>
                        <th>Пользователь</th>
                        <th>Тип события</th>
                        <th>Детали</th>
                    </tr>
                    </thead>

                    <tbody>
                    {events.map((event) => (
                        <tr key={event.id}>
                            <td>
                                {formatDateTime(
                                    event.createdAt,
                                )}
                            </td>

                            <td>
                                <OrganizationCell
                                    organizationId={
                                        event.organizationId
                                    }
                                    organizationNameById={
                                        organizationNameById
                                    }
                                />
                            </td>

                            <td>
                                <AuditActor event={event} />
                            </td>

                            <td>
                                <span className="event-type-badge">
                                    {getAuditEventTypeLabel(
                                        event.eventType,
                                    )}
                                </span>
                            </td>

                            <td>
                                {Object.keys(
                                    event.details,
                                ).length === 0 ? (
                                    <span className="muted">
                                        —
                                    </span>
                                ) : (
                                    <button
                                        type="button"
                                        className="secondary-button"
                                        onClick={() =>
                                            onOpenDetails(event)
                                        }
                                    >
                                        Показать детали
                                    </button>
                                )}
                            </td>
                        </tr>
                    ))}
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
    organizationNameById: Map<string, string>
}

function OrganizationCell({
                              organizationId,
                              organizationNameById,
                          }: OrganizationCellProps) {
    const organizationName =
        organizationNameById.get(organizationId)

    return (
        <span
            className={
                organizationName
                    ? undefined
                    : 'muted'
            }
            title={organizationId}
        >
            {organizationName ?? organizationId}
        </span>
    )
}

export default AuditTable
