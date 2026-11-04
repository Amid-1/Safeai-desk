// frontend/src/features/knowledge/ui/BaseDeletionHistory.tsx
import {useEffect, useState} from 'react'
import {listBaseDeletions} from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'
import type {BaseDeletionOperation} from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'
import {KnowledgeDeletionProgress, isUnfinishedDeletion} from './KnowledgeDeletionProgress'
import '../styles/KnowledgeDeletionConfirmation.css'

/** История сохраняется и читается независимо от разрешения на новые удаления. */
export function BaseDeletionHistory({refreshVersion}: {refreshVersion: number}) {
    const [operations, setOperations] = useState<BaseDeletionOperation[]>([])
    const [error, setError] = useState('')
    useEffect(() => {
        const controller = new AbortController()
        const load = () => {
            void listBaseDeletions(controller.signal)
                .then(items => { if (!controller.signal.aborted) { setOperations(items); setError('') } })
                .catch(() => { if (!controller.signal.aborted) setError('Статус удаления временно недоступен.') })
        }
        load()
        const handle = window.setInterval(load, 12_000)
        return () => { controller.abort(); window.clearInterval(handle) }
    }, [refreshVersion])
    if (!operations.length && !error) return null
    return <section id="knowledge-base-deletion-history" aria-label="Операции удаления баз знаний"
        className="knowledge-erasure-history-section">
        <h2>История удаления баз знаний</h2>
        {error && <p role="alert">{error} Последний известный статус может быть устаревшим.</p>}
        <details open={operations.some(operation => isUnfinishedDeletion(operation.state))}>
            <summary>Операции ({operations.length})</summary>
            <div className="knowledge-erasure-history">
                {operations.map(operation => <article key={operation.operationId}>
                    <p>База: <code>{operation.knowledgeBaseId}</code></p>
                    <KnowledgeDeletionProgress operation={operation}/>
                </article>)}
            </div>
        </details>
    </section>
}
