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
    const email =
        normalizeOptionalText(
            event.actorEmail,
        )

    const displayName =
        normalizeOptionalText(
            event.actorDisplayName,
        )

    const {
        actorUserId,
        actorOrganizationId,
    } = event

    const isSystemActor =
        email === null
        && displayName === null
        && actorUserId === null
        && actorOrganizationId === null

    if (isSystemActor) {
        return (
            <span className="muted">
                Система
            </span>
        )
    }

    const primaryLabel =
        email
        ?? displayName
        ?? (
            actorUserId
                ? 'Пользователь'
                : 'Организация'
        )

    const shouldShowDisplayName =
        displayName !== null
        && displayName !== primaryLabel

    return (
        <div className="audit-actor">
            <span>
                {primaryLabel}
            </span>

            {shouldShowDisplayName && (
                <span className="muted">
                    {displayName}
                </span>
            )}

            {actorUserId && (
                <span className="muted">
                    Пользователь:
                    {' '}
                    <code>
                        {actorUserId}
                    </code>
                </span>
            )}

            {actorOrganizationId && (
                <span className="muted">
                    Организация:
                    {' '}
                    <code>
                        {actorOrganizationId}
                    </code>
                </span>
            )}
        </div>
    )
}

function normalizeOptionalText(
    value: string | null,
): string | null {
    if (value === null) {
        return null
    }

    const normalized = value.trim()

    return normalized || null
}

export default AuditActor