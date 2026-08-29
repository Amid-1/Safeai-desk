import {
    useRef,
} from 'react'
import {
    useParams,
} from 'react-router-dom'
import {
    ErrorState,
    LoadingState,
} from '../components/StateBlock'
import {
    KnowledgeDetailsContent,
} from '../components/knowledge/details/KnowledgeDetailsContent'
import {
    KnowledgeUploadDialog,
} from '../components/knowledge/details/KnowledgeUploadDialog'
import {
    KnowledgeVersionsDialog,
} from '../components/knowledge/details/KnowledgeVersionsDialog'
import {
    useKnowledgeDetailsData,
} from './useKnowledgeDetailsData'
import {
    useKnowledgeDocumentActions,
} from './useKnowledgeDocumentActions'
import {
    useKnowledgeUploadController,
} from './useKnowledgeUploadController'
import {
    useKnowledgeVersionsController,
} from './useKnowledgeVersionsController'
import './KnowledgeDetailsPage.css'

function KnowledgeDetailsPage() {
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
            baseEnabled:
                data.base?.enabled ?? false,
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

    if (data.loading) {
        return (
            <LoadingState message="Загрузка базы знаний..." />
        )
    }

    if (!data.base) {
        return (
            <ErrorState
                message={
                    data.error
                    || 'База знаний не найдена.'
                }
            />
        )
    }

    return (
        <div className="page knowledge-details knowledge-details-page">
            <KnowledgeDetailsContent
                base={data.base}
                health={data.health}
                documents={data.documents}
                documentsPage={data.documentsPage}
                documentsLoading={
                    data.documentsLoading
                }
                documentsError={
                    data.documentsError
                }
                error={data.error}
                documentsSectionRef={
                    documentsSectionRef
                }
                busy={upload.busy}
                reindexingDocumentId={
                    documentActions.reindexingDocumentId
                }
                openingDocumentId={
                    documentActions.openingDocumentId
                }
                downloadingDocumentId={
                    documentActions.downloadingDocumentId
                }
                setDocumentPage={
                    data.setDocumentPage
                }
                openUpload={upload.openUpload}
                openDocumentPreview={
                    documentActions.openDocumentPreview
                }
                downloadDocumentFile={
                    documentActions.downloadDocumentFile
                }
                openVersionHistory={
                    versions.openVersionHistory
                }
                requestReindex={
                    documentActions.requestReindex
                }
                onRetryOverview={
                    data.retryOverview
                }
                onRetryDocuments={
                    data.retryDocuments
                }
            />

            {upload.uploadTarget && (
                <KnowledgeUploadDialog
                    uploadTarget={
                        upload.uploadTarget
                    }
                    uploadName={upload.uploadName}
                    busy={upload.busy}
                    uploadFile={upload.uploadFile}
                    uploadError={upload.uploadError}
                    uploadRequestId={
                        upload.uploadRequestId
                    }
                    requestIdCopied={
                        upload.requestIdCopied
                    }
                    uploadSubmitDisabled={
                        upload.uploadSubmitDisabled
                    }
                    onClose={upload.closeUpload}
                    onUploadNameChange={
                        upload.changeUploadName
                    }
                    onFileSelect={upload.selectFile}
                    onCopyRequestId={
                        upload.copyUploadRequestId
                    }
                    onSubmit={upload.submitUpload}
                />
            )}

            {versions.versionDocument && (
                <KnowledgeVersionsDialog
                    versionDocument={
                        versions.versionDocument
                    }
                    versionsPage={
                        versions.versionsPage
                    }
                    versionsLoading={
                        versions.versionsLoading
                    }
                    versionsError={
                        versions.versionsError
                    }
                    openingVersionId={
                        versions.openingVersionId
                    }
                    downloadingVersionId={
                        versions.downloadingVersionId
                    }
                    onClose={
                        versions.closeVersionHistory
                    }
                    onOpenVersion={
                        versions.openDocumentVersionPreview
                    }
                    onDownloadVersion={
                        versions.downloadDocumentVersion
                    }
                />
            )}
        </div>
    )
}

export default KnowledgeDetailsPage
