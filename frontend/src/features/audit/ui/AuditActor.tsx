// frontend/src/features/audit/ui/AuditActor.tsx
// ============================================================
// frontend/src/features/audit/ui/AuditActor.tsx
// ============================================================

import { useState } from 'react'
import type {
    AuditEvent,
} from '../api/adminAuditApi'

type AuditActorProps = {
    event: AuditEvent
    showIds?: boolean
    hideIds?: boolean
    onFilterActor?: () => void
    filterDisabled?: boolean
}

function AuditActor({
    event,
    showIds = false,
    hideIds = false,
    onFilterActor,
    filterDisabled = false,
}: AuditActorProps) {
    const [copyStatus, setCopyStatus] = useState('')

    async function copyUserId() {
        if (!event.actorUserId) return
        try {
            await navigator.clipboard.writeText(event.actorUserId)
            setCopyStatus('Идентификатор скопирован')
        } catch {
            setCopyStatus('Не удалось скопировать идентификатор')
        }
    }

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
                {event.eventType === 'USER_LOGIN_FAILED' ? 'Пользователь не установлен' : 'Система'}
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
            'Пользователь без имени'
        )

    const secondaryText =
        email && displayName
            ? displayName
            : null

    return (
        <div className="audit-actor audit-identity">
            {onFilterActor && event.actorUserId ? (
                <button type="button" className="audit-identity__primary audit-actor-link"
                    disabled={filterDisabled}
                    title={filterDisabled ? 'Сначала примените фильтры или дождитесь загрузки' : 'Показать события пользователя за выбранный период'}
                    aria-label={'Показать события: ' + primaryText} onClick={onFilterActor}>
                    {primaryText}
                </button>
            ) : <strong
                className={
                    'audit-identity__primary'
                }
            >
                {primaryText}
            </strong>}

            {!hasReadableIdentity && (
                <span className="audit-identity__historical-note">
                    Имя пользователя не сохранено в исторической записи.
                </span>
            )}

            {secondaryText && (
                <span
                    className={
                        'audit-identity__secondary'
                    }
                >
                    {secondaryText}
                </span>
            )}

            {!hideIds && (showIds || !hasReadableIdentity)
                && event.actorUserId
                && (
                    <span
                        className={
                            'audit-identity__meta audit-copy-line'
                        }
                        title={event.actorUserId}
                    >
                        <span>Идентификатор пользователя: {' '}
                        {shortAuditIdentifier(event.actorUserId)}
                        </span>
                        <button
                            type="button"
                            className="audit-copy-uuid"
                            aria-label="Скопировать идентификатор пользователя"
                            title="Скопировать идентификатор пользователя"
                            onClick={() => void copyUserId()}
                        >
                            Копировать
                        </button>
                        {copyStatus && <span role="status" className="audit-copy-status">{copyStatus}</span>}
                    </span>
                )}

            {!hideIds && showIds
                && event.actorOrganizationId
                && (
                    <span
                        className={
                            'audit-identity__meta '
                            + 'audit-monospace'
                        }
                    >
                        Идентификатор организации пользователя:
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
