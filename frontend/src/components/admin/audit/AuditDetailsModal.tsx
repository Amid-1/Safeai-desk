// ============================================================
// frontend/src/components/admin/audit/AuditDetailsModal.tsx
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

import Modal from '../../Modal'
import AuditActor from './AuditActor'

type AuditDetailsModalProps = {
    event: AuditEvent
    organizationName: string
    onClose: () => void
}

function AuditDetailsModal({
                               event,
                               organizationName,
                               onClose,
                           }: AuditDetailsModalProps) {
    return (
        <Modal
            title={getAuditEventTypeLabel(
                event.eventType,
            )}
            onClose={onClose}
        >
            <div className="audit-details-modal">
                <dl className="audit-details-list">
                    <div>
                        <dt>Дата и время</dt>
                        <dd>
                            {formatDateTime(
                                event.createdAt,
                            )}
                        </dd>
                    </div>

                    <div>
                        <dt>Организация</dt>
                        <dd>{organizationName}</dd>
                    </div>

                    <div>
                        <dt>Пользователь</dt>
                        <dd>
                            <AuditActor event={event} />
                        </dd>
                    </div>

                    <div>
                        <dt>Тип события</dt>
                        <dd>
                            {getAuditEventTypeLabel(
                                event.eventType,
                            )}
                        </dd>
                    </div>

                    <div>
                        <dt>ID события</dt>
                        <dd className="audit-monospace">
                            {event.id}
                        </dd>
                    </div>
                </dl>

                <h3>Технические детали</h3>

                <pre className="audit-json">
                    {JSON.stringify(
                        event.details,
                        null,
                        2,
                    )}
                </pre>

                <div className="modal-actions">
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={onClose}
                    >
                        Закрыть
                    </button>
                </div>
            </div>
        </Modal>
    )
}

export default AuditDetailsModal
