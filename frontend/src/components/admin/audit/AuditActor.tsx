// ============================================================
// frontend/src/components/admin/audit/AuditActor.tsx
// ============================================================
import type {
    AuditEvent,
} from '../../../api/adminApi'

type AuditActorProps = {
    event: AuditEvent
}

function AuditActor({
                        event,
                    }: AuditActorProps) {
    if (
        !event.userEmail &&
        !event.userDisplayName
    ) {
        return (
            <span className="muted">
                Система
            </span>
        )
    }

    return (
        <div className="audit-actor">
            <span>
                {event.userEmail ?? '—'}
            </span>

            {event.userDisplayName && (
                <span className="muted">
                    {event.userDisplayName}
                </span>
            )}
        </div>
    )
}

export default AuditActor