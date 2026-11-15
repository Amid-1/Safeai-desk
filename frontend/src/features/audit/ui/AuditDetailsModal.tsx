// frontend/src/features/audit/ui/AuditDetailsModal.tsx
import { useId, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type { AuditEvent } from '../api/adminAuditApi'
import { formatDateTime } from '../../../shared/lib/format'
import Modal from '../../../shared/ui/Modal'
import AuditActor from './AuditActor'
import { auditEventPresentation } from './auditEventPresentation'

export default function AuditDetailsModal({ event, organizationFallbackName, onClose }: {
    event: AuditEvent
    organizationFallbackName?: string
    onClose: () => void
}) {
    const descriptionId = useId()
    const closeButtonRef = useRef<HTMLButtonElement | null>(null)
    const [copyStatus, setCopyStatus] = useState('')
    const presentation = auditEventPresentation(event)
    const { title, result, description, decisionId, fields } = presentation
    const organization = event.targetOrganizationName ?? organizationFallbackName ?? 'Название недоступно'

    async function copyDecision() {
        if (!decisionId) return
        try {
            await navigator.clipboard.writeText(decisionId)
            setCopyStatus('Номер решения скопирован')
        } catch {
            setCopyStatus('Не удалось скопировать. Выделите номер вручную.')
        }
    }

    return <Modal title={title} onClose={onClose} descriptionId={descriptionId}
        initialFocusRef={closeButtonRef} size="lg" className="audit-event-modal"
        footer={<div className="modal-actions"><button ref={closeButtonRef} type="button" className="secondary-button" onClick={onClose}>Закрыть</button></div>}>
        <div className="audit-details-modal audit-details-v2">
            <div className="audit-event-summary">
                <span className={'event-type-badge event-type-badge--' + result.tone}>{result.label}</span>
                <p id={descriptionId}><strong>{event.eventType === 'MODEL_ROUTE_DENIED' ? 'Причина блокировки' : 'Что произошло'}</strong></p>
                <p>{description}</p>
            </div>
            <dl className="audit-details-list">
                <Detail term="Дата и время">{formatDateTime(event.createdAt)}</Detail>
                <Detail term="Организация">{organization}</Detail>
                <Detail term="Кто выполнил действие"><AuditActor event={event} /></Detail>
                {fields.map(({ label, value }) => <Detail key={label} term={label}>{value}</Detail>)}
            </dl>
            {decisionId && <div className="audit-decision-card">
                <strong>Номер решения безопасности</strong>
                <p className="audit-copy-line"><code>{decisionId}</code>
                    <button type="button" className="secondary-button" onClick={() => void copyDecision()}>Копировать номер решения</button>
                </p>
                {copyStatus && <p role="status">{copyStatus}</p>}
                <a className="audit-decision-link" href={'/admin/models?section=diagnostics&decisionId=' + encodeURIComponent(decisionId)}>
                    Открыть диагностику запроса
                </a>
                <small>Диагностика проверяет сохранённое решение на момент запроса.</small>
            </div>}
            {event.detailsInvalid && <p className="audit-directory-warning" role="alert">
                Подробности события недоступны: сервер вернул данные неправильного формата.
            </p>}
            {event.detailsTruncated && <p className="audit-directory-warning" role="status">
                Подробности события ограничены по размеру или количеству полей.
            </p>}
            <details className="audit-technical-details">
                <summary>Технические подробности</summary>
                <dl className="audit-details-list">
                    <Detail term="Код события"><code>{event.eventType}</code></Detail>
                    <Detail term="Идентификатор события"><code>{event.id}</code></Detail>
                    <Detail term="Идентификатор организации"><code>{event.targetOrganizationId}</code></Detail>
                    <Detail term="Пользователь"><AuditActor event={event} showIds /></Detail>
                </dl>
                <pre className="audit-json">{JSON.stringify(event.details, null, 2)}</pre>
            </details>
        </div>
    </Modal>
}

function Detail({ term, children }: { term: string; children: ReactNode }) {
    return <div><dt>{term}</dt><dd>{children}</dd></div>
}
