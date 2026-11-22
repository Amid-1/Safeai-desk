// frontend/src/pages/KnowledgeDetailsPage.tsx
// Маршрут подробной информации о базе знаний; параметры получаются контроллером.
import { KnowledgeDetailsWorkspace } from '../features/knowledge/ui/KnowledgeDetailsWorkspace'
import { useParams } from 'react-router-dom'
import '../features/knowledge/styles/KnowledgeDetailsPage.css'

function KnowledgeDetailsPage() {
    const { knowledgeBaseId } = useParams()
    return <KnowledgeDetailsWorkspace key={knowledgeBaseId} />
}

export default KnowledgeDetailsPage
