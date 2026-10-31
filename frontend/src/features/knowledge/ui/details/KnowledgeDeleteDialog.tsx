// frontend/src/features/knowledge/ui/details/KnowledgeDeleteDialog.tsx
import {useId, useState} from 'react'
import type {KnowledgeDocument} from '../../../../entities/knowledge/api/knowledgeDocumentApi'
import {getApiErrorMessage} from '../../../../shared/api/http'
import Modal from '../../../../shared/ui/Modal'
import {matchesKnowledgeDeletionName} from '../../lib/knowledgeDeletionConfirmation'
import '../../styles/KnowledgeDeletionConfirmation.css'

export function KnowledgeDeleteDialog({document, busy, onCancel, onConfirm}: {
    document: KnowledgeDocument
    busy: boolean
    onCancel: () => void
    onConfirm: () => Promise<void>
}) {
    const [name, setName] = useState('')
    const [error, setError] = useState('')
    const inputId = useId()
    const matches = matchesKnowledgeDeletionName(name, document.name)

    async function confirm() {
        if (busy || !matches) return
        setError('')
        try {
            await onConfirm()
        } catch (cause) {
            // Сохраняем окно открытым и прежний ключ идемпотентности для безопасного повтора.
            setError(getApiErrorMessage(cause,
                'Не удалось подтвердить удаление. Проверьте статус операции перед повтором.'))
        }
    }

    return (
        <Modal title="Безвозвратное удаление документа"
            size="md" onClose={onCancel} closeDisabled={busy}>
            <p>Будут удалены документ «{document.name}», все его версии и связанные
                поисковые данные. После завершения восстановить исходные файлы
                обычным способом будет нельзя.</p>
            <p>После подтверждения система начнёт удаление. Результат можно
                проверить в истории удаления. Записи о выполненных действиях сохраняются.</p>
            <div className="knowledge-deletion-confirmation__field">
                <label htmlFor={inputId}>Название документа</label>
                <input id={inputId} type="text" value={name} disabled={busy}
                    autoComplete="off" spellCheck={false} maxLength={255}
                    onChange={event => setName(event.target.value)}/>
                <p className="knowledge-deletion-confirmation__hint">
                    Введите полное название. Регистр не важен, но символы других
                    алфавитов и похожие цифры не заменяют исходные буквы.
                </p>
            </div>
            {error && <p role="alert" className="knowledge-deletion-confirmation__notice">{error}</p>}
            <div className="modal-actions">
                <button type="button" className="secondary-button"
                    onClick={onCancel} disabled={busy}>Отмена</button>
                <button type="button" className="danger-button"
                    disabled={busy || !matches}
                    onClick={() => { void confirm() }}>
                    {busy ? 'Отправляем запрос…' : 'Подтвердить удаление документа'}
                </button>
            </div>
        </Modal>
    )
}
