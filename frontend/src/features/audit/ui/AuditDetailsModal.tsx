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
    const presentation = auditEventPresentation(event)
    const { title, result, description, decisionId, fields } = presentation
    const organization = event.targetOrganizationName ?? organizationFallbackName ?? 'Название недоступно'

    const toneIcon = result.tone === 'success' ? '✓' : result.tone === 'neutral' ? 'i' : '!'

    return <Modal title={title} onClose={onClose} descriptionId={descriptionId}
        initialFocusRef={closeButtonRef} size="lg" className="audit-event-modal"
        footer={<div className="modal-actions"><button ref={closeButtonRef} type="button" className="secondary-button" onClick={onClose}>Закрыть</button></div>}>
        <div className="audit-details-modal audit-details-v2">
            <div className={'audit-event-summary audit-event-summary--' + result.tone}>
                <span className="audit-event-summary__icon" aria-hidden="true">{toneIcon}</span>
                <div className="audit-event-summary__content">
                    <div className="audit-event-summary__meta">
                        <span className={'event-type-badge event-type-badge--' + result.tone}>{result.label}</span>
                        <time dateTime={event.createdAt}>{formatDateTime(event.createdAt)}</time>
                    </div>
                    <strong>{event.eventType === 'MODEL_ROUTE_DENIED' ? 'Причина блокировки' : 'Что произошло'}</strong>
                    <p id={descriptionId}>{description}</p>
                </div>
            </div>
            <dl className="audit-details-list">
                <Detail term="Организация">{organization}</Detail>
                <Detail term="Пользователь / система"><AuditActor event={event} hideIds /></Detail>
                {fields.map(({ label, value }) => <Detail key={label} term={label}>{value}</Detail>)}
            </dl>
            {decisionId && <div className="audit-decision-card">
                <strong>Номер решения безопасности</strong>
                <CopyValue key={decisionId} value={decisionId} label="Копировать номер решения" />
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
            <SupportDetails key={event.id} event={event} />
        </div>
    </Modal>
}

function SupportDetails({ event }: { event: AuditEvent }) {
    const [status, setStatus] = useState('')
    // Use only the already normalized, authorized audit API response. Do not fetch raw records.
    const report = JSON.stringify({
        reportVersion: 1,
        id: event.id,
        eventType: event.eventType,
        createdAt: event.createdAt,
        targetOrganizationId: event.targetOrganizationId,
        actorOrganizationId: event.actorOrganizationId,
        actorUserId: event.actorUserId,
        detailsInvalid: event.detailsInvalid,
        detailsTruncated: event.detailsTruncated,
        details: event.detailsInvalid ? null : event.details,
    }, null, 2)

    async function copyReport() {
        try {
            await navigator.clipboard.writeText(report)
            setStatus('Данные скопированы')
        } catch {
            setStatus('Не удалось скопировать. Раскройте отчёт JSON и скопируйте его вручную.')
        }
    }

    return <details className="audit-technical-details">
        <summary><span>Сведения для технической поддержки</span><small>Для разбора ошибок и обращений пользователей</small></summary>
        <div className="audit-support-report">
            <dl className="audit-support-list">
                <Detail term="Идентификатор события"><code>{event.id}</code></Detail>
            </dl>
            <p>Если нужна помощь с этим событием, передайте диагностический отчёт в авторизованную службу поддержки.</p>
            <button type="button" className="audit-value-copy__button audit-support-copy" onClick={() => void copyReport()}>
                <CopyIcon />Копировать данные для поддержки
            </button>
            {status && <span role="status" className="audit-value-copy__status">{status}</span>}
        </div>
        <details className="audit-json-details">
            <summary>Диагностический отчёт (JSON)</summary>
            <pre className="audit-json" tabIndex={0}>{report}</pre>
        </details>
    </details>
}

function CopyIcon() {
    return <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/></svg>
}

function CopyValue({ value, label }: { value: string; label: string }) {
    const [status, setStatus] = useState('')
    async function copy() {
        try {
            await navigator.clipboard.writeText(value)
            setStatus('Скопировано')
        } catch {
            setStatus('Не удалось скопировать. Выделите значение вручную.')
        }
    }
    return <div className="audit-value-copy">
        <code>{value}</code>
        <button type="button" className="audit-value-copy__button" aria-label={label} onClick={() => void copy()}>
            <CopyIcon />
            Копировать
        </button>
        {status && <span role="status" className="audit-value-copy__status">{status}</span>}
    </div>
}

function Detail({ term, children }: { term: string; children: ReactNode }) {
    return <div><dt>{term}</dt><dd>{children}</dd></div>
}
