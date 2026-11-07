// frontend/src/features/knowledge/lib/knowledgeDeletionStatus.ts
import type {KnowledgeDeletionState} from '../../../entities/knowledge/api/knowledgeDocumentDeletionApi'

/**
 * Только подтверждённый DELETED означает завершение удаления.
 * Неизвестные серверные состояния остаются незавершёнными.
 */
export function isUnfinishedDeletion(state: KnowledgeDeletionState): boolean {
    return state !== 'DELETED'
}
