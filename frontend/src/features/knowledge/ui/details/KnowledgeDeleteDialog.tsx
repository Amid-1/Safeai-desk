// frontend/src/features/knowledge/ui/details/KnowledgeDeleteDialog.tsx
import {useState} from 'react'
import type {KnowledgeDocument} from '../../../../entities/knowledge/api/knowledgeDocumentApi'
import Modal from '../../../../shared/ui/Modal'

export function KnowledgeDeleteDialog({document, busy, onCancel, onConfirm}: {
    document: KnowledgeDocument
    busy: boolean
    onCancel: () => void
    onConfirm: () => Promise<void>
}) {
    const [name, setName] = useState('')
    return (
        <Modal title="Безвозвратное удаление документа"
            size="md" onClose={onCancel} closeDisabled={busy}>
            <p>Удалить документ «{document.name}» без возможности восстановления?
                Все версии и исходные файлы будут удалены. История выполненных
                действий сохранится в соответствии с правилами хранения.</p>
            <p>Для подтверждения введите точное название документа:</p>
            <label>
                Название документа
                <input type="text" value={name} disabled={busy}
                    autoComplete="off" maxLength={255}
                    onChange={event => setName(event.target.value)} />
            </label>
            <div className="modal-actions">
                <button type="button" className="secondary-button"
                    onClick={onCancel} disabled={busy}>Отмена</button>
                <button type="button" className="danger-button"
                    disabled={busy || name !== document.name}
                    onClick={() => { void onConfirm().catch(() => undefined) }}>
                    {busy ? 'Отправляем запрос…' : 'Удалить безвозвратно'}
                </button>
            </div>
        </Modal>
    )
}
