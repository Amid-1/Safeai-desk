// ============================================================
// frontend/src/components/admin/audit/AuditActor.tsx
// ============================================================

import type {
    AuditEvent,
} from '../../../api/adminApi'

type AuditActorProps = {
    event: AuditEvent
    showIds?: boolean
}

function AuditActor({
    event,
    showIds = false,
}: AuditActorProps) {
    const displayName =
        event.actorDisplayName?.trim()
        || null

    const systemDisplayName =
        displayName?.toUpperCase()
        === 'SYSTEM'

    const isSystem =
        event.actorUserId === null
        && event.actorEmail === null
        && event.actorOrganizationId
            === null
        && (
            displayName === null
            || systemDisplayName
        )

    if (isSystem) {
        return (
            <span className="audit-identity__system">
                Система
            </span>
        )
    }

    const email =
        event.actorEmail?.trim()
        || null

    const hasReadableIdentity =
        Boolean(email || displayName)

    const primaryText =
        email
        ?? displayName
        ?? (
            'Исторический инициатор'
        )

    const secondaryText =
        email && displayName
            ? displayName
            : null

    return (
        <div className="audit-actor audit-identity">
            <strong
                className={
                    'audit-identity__primary'
                }
            >
                {primaryText}
            </strong>

            {secondaryText && (
                <span
                    className={
                        'audit-identity__secondary'
                    }
                >
                    {secondaryText}
                </span>
            )}

            {(showIds || !hasReadableIdentity)
                && event.actorUserId
                && (
                    <span
                        className={
                            'audit-identity__meta '
                            + 'audit-monospace'
                        }
                    >
                        Пользователь ID:
                        {' '}
                        {shortAuditIdentifier(event.actorUserId)}
                    </span>
                )}

            {showIds
                && event.actorOrganizationId
                && (
                    <span
                        className={
                            'audit-identity__meta '
                            + 'audit-monospace'
                        }
                    >
                        Организация инициатора ID:
                        {' '}
                        {
                            shortAuditIdentifier(event.actorOrganizationId)
                        }
                    </span>
                )}
        </div>
    )
}

function shortAuditIdentifier(value: string): string {
    return value.length > 16
        ? `${value.slice(0, 8)}…${value.slice(-4)}`
        : value
}

export default AuditActor
