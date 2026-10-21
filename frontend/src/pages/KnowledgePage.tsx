// frontend/src/pages/KnowledgePage.tsx
// Маршрут каталога баз знаний; UI и операции управляются feature-модулем.
import { KnowledgeDirectoryWorkspace } from '../features/knowledge/ui/KnowledgeDirectoryWorkspace'
import '../features/knowledge/styles/KnowledgePage.css'

function KnowledgePage() {
    return <KnowledgeDirectoryWorkspace />
}

export default KnowledgePage
