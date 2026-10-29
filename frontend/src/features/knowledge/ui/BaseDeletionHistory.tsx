// frontend/src/features/knowledge/ui/BaseDeletionHistory.tsx
import {useEffect, useState} from 'react'
import {listBaseDeletions} from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'
import type {BaseDeletionOperation} from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'

const LABEL: Record<BaseDeletionOperation['state'], string> = {
    DELETING: 'Удаляется',
    NEEDS_REVIEW: 'Требуется проверка администратором',
    BLOCKED_BY_RETENTION: 'Удаление заблокировано политикой хранения',
    DELETED: 'Удалено',
}

/** Non-sensitive operation history remains visible even after the live base row is gone. */
export function BaseDeletionHistory({refreshVersion}: {refreshVersion: number}) {
    const [operations, setOperations] = useState<BaseDeletionOperation[]>([])
    const [error, setError] = useState('')
    useEffect(() => {
        const controller = new AbortController()
        const load = () => {
            void listBaseDeletions(controller.signal)
                .then(items => { if (!controller.signal.aborted) { setOperations(items); setError('') } })
                .catch(() => { if (!controller.signal.aborted) setError('Статус удаления недоступен.') })
        }
        load()
        const handle = window.setInterval(load, 12_000)
        return () => { controller.abort(); window.clearInterval(handle) }
    }, [refreshVersion])
    if (!operations.length && !error) return null
    return <section aria-label="Операции удаления баз знаний">
        <h2>Операции удаления баз знаний</h2>
        {error && <p role="alert">{error}</p>}
        <ul>{operations.map(op => <li key={op.operationId}>
            <code>{op.knowledgeBaseId}</code> — {LABEL[op.state]}
            {op.errorCode && <span> ({op.errorCode})</span>}
        </li>)}</ul>
    </section>
}
