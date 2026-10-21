// frontend/src/features/knowledge/controller/useKnowledgeDetailsPageController.ts
// Композитор хуков данных, документов, версий и загрузки; порядок хуков сохранён.
import { useRef } from 'react'
import { useParams } from 'react-router-dom'
import { useKnowledgeDetailsData } from './useKnowledgeDetailsData'
import { useKnowledgeDocumentActions } from './useKnowledgeDocumentActions'
import { useKnowledgeUploadController } from './useKnowledgeUploadController'
import { useKnowledgeVersionsController } from './useKnowledgeVersionsController'

export function useKnowledgeDetailsPageController() {
    const {
        knowledgeBaseId = '',
    } = useParams()

    const documentsSectionRef =
        useRef<HTMLElement | null>(null)

    const data = useKnowledgeDetailsData(
        knowledgeBaseId,
    )

    const upload = useKnowledgeUploadController({
        knowledgeBaseId,
        documentPage: data.documentPage,
        setDocumentPage: data.setDocumentPage,
        refreshAfterSuccessfulUpload:
            data.refreshAfterSuccessfulUpload,
    })

    const documentActions =
        useKnowledgeDocumentActions({
            knowledgeBaseId,
            canEditDocuments:
                data.access?.canEditDocuments ?? false,
            documentPage: data.documentPage,
            setError: data.setError,
            loadOverview: data.loadOverview,
            loadDocuments: data.loadDocuments,
        })

    const versions =
        useKnowledgeVersionsController({
            knowledgeBaseId,
            setError: data.setError,
        })

    return { data, upload, documentActions, versions, documentsSectionRef }
}
