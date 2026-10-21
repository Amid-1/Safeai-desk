// frontend/src/features/knowledge/ui/KnowledgeDetailsWorkspace.tsx
// Композиция карточки базы знаний, предпросмотра документов и диалогов версий.
import { ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import { KnowledgeDetailsContent } from './details/KnowledgeDetailsContent'
import { KnowledgeUploadDialog } from './details/KnowledgeUploadDialog'
import { KnowledgeVersionsDialog } from './details/KnowledgeVersionsDialog'
import { useKnowledgeDetailsPageController } from '../controller/useKnowledgeDetailsPageController'

export function KnowledgeDetailsWorkspace() {
    const { data, upload, documentActions, versions, documentsSectionRef } =
        useKnowledgeDetailsPageController()

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
                access={data.access}
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
                reindexNotice={documentActions.reindexNotice}
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

            {data.access?.canEditDocuments
                && upload.uploadTarget && (
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

            {data.access?.canEditDocuments
                && versions.versionDocument && (
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
