// frontend/src/features/knowledge/ui/details/KnowledgeDocumentDeleteAction.tsx
import {useState} from 'react'
import type {KnowledgeDocument} from '../../../../entities/knowledge/api/knowledgeDocumentApi'
import Modal from '../../../../shared/ui/Modal'

export function KnowledgeDocumentDeleteAction({document, canDelete, busy, unavailableReason, onDelete}: {
    document: KnowledgeDocument
    canDelete: boolean
    busy: boolean
    unavailableReason: string
    onDelete: (document: KnowledgeDocument) => void
}) {
    const [showReason, setShowReason] = useState(false)

    function handleClick() {
        if (busy) return
        if (!canDelete) {
            // Если удаление недоступно, объясняем причину без запроса на удаление.
            setShowReason(true)
            return
        }
        onDelete(document)
    }

    return <>
        <button type="button" className="secondary-button" disabled={busy}
            title={canDelete
                ? 'Удалить документ и все его версии'
                : 'Удаление сейчас недоступно. Нажмите, чтобы узнать причину'}
            onClick={handleClick}>
            Удалить…
        </button>
        {showReason && <Modal title="Удаление документа недоступно" size="md"
            onClose={() => setShowReason(false)}>
            <p>{unavailableReason || 'Удаление документа сейчас недоступно.'}</p>
            {unavailableReason.includes('отключено')
                ? <p>Чтобы воспользоваться этой функцией, её необходимо включить и проверить.</p>
                : <p>Обновите страницу или повторите попытку позже.</p>}
            <p>Документ «{document.name}» и его файлы остаются без изменений.</p>
            <div className="modal-actions">
                <button type="button" className="secondary-button"
                    onClick={() => setShowReason(false)}>Закрыть</button>
            </div>
        </Modal>}
    </>
}
