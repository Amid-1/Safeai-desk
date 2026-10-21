// frontend/src/components/knowledge/KnowledgeMembersModal.tsx
import type { KnowledgeBase } from '../../api/knowledgeApi'
import './KnowledgeMembersModal.css'
import { useKnowledgeMembersController } from './knowledgeMembers/useKnowledgeMembersController'
import { KnowledgeMembersView } from './knowledgeMembers/KnowledgeMembersView'

type KnowledgeMembersModalProps = {
    knowledgeBase: KnowledgeBase
    onClose: () => void
    onChanged: () => void
}

function KnowledgeMembersInstance({ knowledgeBase, onClose, onChanged }: KnowledgeMembersModalProps) {
    const controller = useKnowledgeMembersController(knowledgeBase, onChanged)
    return <KnowledgeMembersView {...controller} knowledgeBase={knowledgeBase} onClose={onClose} />
}

// Смена базы размонтирует предыдущий ACL-контроллер и отменяет его запросы.
function KnowledgeMembersModal(props: KnowledgeMembersModalProps) {
    return <KnowledgeMembersInstance key={props.knowledgeBase.id} {...props} />
}

export default KnowledgeMembersModal
