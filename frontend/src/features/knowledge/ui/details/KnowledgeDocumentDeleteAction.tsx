import {useState} from 'react'
import type {KnowledgeDocument} from '../../../../entities/knowledge/api/knowledgeDocumentApi'
import type {KnowledgeDocumentDeletion} from '../../../../entities/knowledge/api/knowledgeDocumentDeletionApi'
import {KnowledgeErasureDialog} from '../KnowledgeErasureDialog'

export function KnowledgeDocumentDeleteAction({document, canDelete, busy, unavailableReason,
    activeOperation, onOpenOperation, onDelete}: {
    document: KnowledgeDocument
    canDelete: boolean
    busy: boolean
    unavailableReason: string
    activeOperation?: KnowledgeDocumentDeletion
    onOpenOperation?: (document: KnowledgeDocument, operation: KnowledgeDocumentDeletion) => void
    onDelete: (document: KnowledgeDocument) => void
}) {
    const [showReason, setShowReason] = useState(false)

    function handleClick() {
        if (busy) return
        if (activeOperation) {
            onOpenOperation?.(document, activeOperation)
        } else if (!canDelete) {
            // Запрещённое действие разъясняется без обращения к DELETE API.
            setShowReason(true)
        } else {
            onDelete(document)
        }
    }

    return <>
        <button type="button" className="secondary-button" disabled={busy}
            title={activeOperation ? 'Посмотреть уже принятую операцию удаления'
                : canDelete ? 'Удалить документ и все его версии'
                    : 'Удаление недоступно. Нажмите, чтобы узнать причину'}
            onClick={handleClick}>
            {activeOperation ? 'Статус удаления…' : 'Удалить…'}
        </button>
        {showReason && <KnowledgeErasureDialog mode="unavailable" kind="document"
            entityName={document.name} reason={unavailableReason || 'Удаление документа сейчас недоступно.'}
            checkFailed={!unavailableReason.includes('отключено')}
            onClose={() => setShowReason(false)}/>}
    </>
}
