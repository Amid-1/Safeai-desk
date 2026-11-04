// frontend/src/features/knowledge/ui/KnowledgeDetailsWorkspace.tsx
// Композиция карточки базы знаний, предпросмотра документов и диалогов версий.
import { useNavigate } from 'react-router-dom'
import { KnowledgeBaseDeleteAction } from './KnowledgeBaseDeleteAction'
import { ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import ConfirmDialog from '../../../shared/ui/ConfirmDialog'
import { KnowledgeDetailsContent } from './details/KnowledgeDetailsContent'
import { KnowledgeUploadDialog } from './details/KnowledgeUploadDialog'
import { KnowledgeVersionsDialog } from './details/KnowledgeVersionsDialog'
import { KnowledgeDeleteDialog } from './details/KnowledgeDeleteDialog'
import { KnowledgeErasureDialog } from './KnowledgeErasureDialog'
import { useKnowledgeDetailsPageController } from '../controller/useKnowledgeDetailsPageController'

export function KnowledgeDetailsWorkspace() {
    const navigate = useNavigate()
    const { data, upload, documentActions, versions, deletion, documentsSectionRef } =
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
            {data.access?.administrator && <div className="knowledge-base-delete-toolbar">
                <KnowledgeBaseDeleteAction base={data.base}
                    onAccepted={() => navigate('/knowledge')}/>
            </div>}
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
                changingEnabledId={documentActions.changingEnabledId}
                canDeleteDocuments={deletion.available}
                canShowDeleteAction={deletion.canShowAction}
                deleteUnavailableReason={deletion.unavailableReason}
                requestDeletion={deletion.requestDeletion}
                openDeletionOperation={deletion.openOperation}
                deletionOperations={deletion.operations}
                deletionHistoryError={deletion.historyError}
                requestEnabledChange={documentActions.requestEnabledChange}
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
                && documentActions.enabledTarget && (
                <ConfirmDialog
                    title={documentActions.enabledTarget.enabled
                        ? 'Отключить документ'
                        : 'Включить документ'}
                    message={documentActions.enabledTarget.enabled
                        ? `Документ «${documentActions.enabledTarget.name}» перестанет использоваться `
                            + 'в ответах AI. Он останется в базе знаний, и его можно будет включить снова.'
                        : `Документ «${documentActions.enabledTarget.name}» снова станет доступен `
                            + 'для поиска и ответов AI, если он обработан и готов к использованию.'}
                    confirmText={documentActions.enabledTarget.enabled
                        ? 'Отключить'
                        : 'Включить'}
                    danger={documentActions.enabledTarget.enabled}
                    onCancel={documentActions.cancelEnabledChange}
                    onConfirm={documentActions.confirmEnabledChange}
                />
            )}

            {deletion.target && (
                <KnowledgeDeleteDialog
                    key={deletion.target.document.id}
                    document={deletion.target.document}
                    busy={deletion.busy}
                    onCancel={deletion.cancelDeletion}
                    onConfirm={deletion.confirmDeletion}
                />
            )}

            {deletion.operationTarget && deletion.visibleOperation && (
                <KnowledgeErasureDialog key={deletion.visibleOperation.operationId}
                    kind="document" mode="operation"
                    entityName={deletion.operationTarget.documentName}
                    operation={deletion.visibleOperation}
                    statusError={deletion.historyError
                        ? 'Не удалось обновить историю операций.' : ''}
                    historyHref="#knowledge-document-deletion-history"
                    onClose={deletion.closeOperation}/>
            )}

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
