// frontend/src/features/knowledge/ui/details/KnowledgeDeleteDialog.tsx
import {useState} from 'react'
import type {KnowledgeDocument} from '../../../../entities/knowledge/api/knowledgeDocumentApi'
import {getApiErrorMessage} from '../../../../shared/api/http'
import {KnowledgeErasureDialog} from '../KnowledgeErasureDialog'

/** Подтверждение документа использует общий безопасный диалог удаления. */
export function KnowledgeDeleteDialog({document, busy, onCancel, onConfirm}: {
    document: KnowledgeDocument
    busy: boolean
    onCancel: () => void
    onConfirm: () => Promise<void>
}) {
    const [name, setName] = useState('')
    const [error, setError] = useState('')

    async function confirm() {
        setError('')
        try {
            await onConfirm()
        } catch (cause) {
            // Сохраняем окно и ключ идемпотентности в контроллере после ошибки сети.
            setError(getApiErrorMessage(cause,
                'Не удалось подтвердить удаление. Проверьте статус операции перед повтором.'))
        }
    }

    return <KnowledgeErasureDialog kind="document" mode="confirm"
        entityName={document.name} typedName={name} onTypedNameChange={setName}
        busy={busy} error={error} onClose={onCancel} onConfirm={() => { void confirm() }}/>
}
