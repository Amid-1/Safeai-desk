// ============================================================
// frontend/src/components/admin/audit/AuditDetailsModal.tsx
// ============================================================

import {
    useId,
    useMemo,
    useRef,
} from 'react'
import type {
    ReactNode,
} from 'react'
import type {
    AuditEvent,
} from '../../../api/adminApi'
import {
    getAuditEventTypeLabel,
} from '../../../constants/auditEvents'
import {
    formatDateTime,
} from '../../../utils/format'
import Modal from '../../Modal'
import AuditActor from './AuditActor'

type AuditDetailsModalProps = {
    event: AuditEvent
    organizationFallbackName?: string
    onClose: () => void
}

function AuditDetailsModal({
    event,
    organizationFallbackName,
    onClose,
}: AuditDetailsModalProps) {
    const descriptionId = useId()

    const closeButtonRef =
        useRef<HTMLButtonElement | null>(
            null,
        )

    const formattedDetails =
        useMemo(
            () =>
                JSON.stringify(
                    event.details,
                    null,
                    2,
                ),
            [event.details],
        )

    const organizationName =
        event.targetOrganizationName
        ?? organizationFallbackName
        ?? 'Название недоступно'

    return (
        <Modal
            title={
                getAuditEventTypeLabel(
                    event.eventType,
                )
            }
            onClose={onClose}
            descriptionId={
                descriptionId
            }
            initialFocusRef={
                closeButtonRef
            }
            size="lg"
        >
            <p
                id={descriptionId}
                className="modal-subtitle"
            >
                Историческое событие аудита.
                Время отображается в часовом
                поясе браузера.
            </p>

            <div className="audit-details-modal">
                <dl className="audit-details-list">
                    <Detail term="Дата и время">
                        {
                            formatDateTime(
                                event.createdAt,
                            )
                        }
                    </Detail>

                    <Detail
                        term="Целевая организация"
                    >
                        <div
                            className={
                                'audit-identity '
                                + 'audit-organization'
                            }
                        >
                            <strong
                                className={
                                    'audit-identity__primary'
                                }
                            >
                                {organizationName}
                            </strong>

                            <span
                                className={
                                    'audit-identity__meta '
                                    + 'audit-monospace'
                                }
                            >
                                {
                                    event.targetOrganizationId
                                }
                            </span>
                        </div>
                    </Detail>

                    <Detail term="Инициатор">
                        <AuditActor
                            event={event}
                            showIds
                        />
                    </Detail>

                    <Detail term="Тип события">
                        {
                            getAuditEventTypeLabel(
                                event.eventType,
                            )
                        }
                    </Detail>

                    <Detail term="Код события">
                        <span className="audit-monospace">
                            {event.eventType}
                        </span>
                    </Detail>

                    <Detail term="ID события">
                        <span className="audit-monospace">
                            {event.id}
                        </span>
                    </Detail>
                </dl>

                <h3>Технические детали</h3>

                {event.detailsInvalid && (
                    <div
                        className={
                            'audit-directory-warning'
                        }
                        role="alert"
                    >
                        Backend вернул details
                        неправильного типа.
                        Для безопасного отображения
                        используется пустой объект.
                    </div>
                )}

                {event.detailsTruncated && (
                    <div
                        className={
                            'audit-directory-warning'
                        }
                        role="status"
                        aria-live="polite"
                    >
                        Детали ограничены по размеру,
                        глубине или количеству полей.
                    </div>
                )}

                <pre className="audit-json">
                    {formattedDetails}
                </pre>

                <div className="modal-actions">
                    <button
                        ref={closeButtonRef}
                        type="button"
                        className={
                            'secondary-button'
                        }
                        onClick={onClose}
                    >
                        Закрыть
                    </button>
                </div>
            </div>
        </Modal>
    )
}

type DetailProps = {
    term: string
    children: ReactNode
}

function Detail({
    term,
    children,
}: DetailProps) {
    return (
        <div>
            <dt>{term}</dt>
            <dd>{children}</dd>
        </div>
    )
}

export default AuditDetailsModal
