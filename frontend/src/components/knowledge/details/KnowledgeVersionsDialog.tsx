import type { KnowledgeDocument, KnowledgeDocumentVersion } from '../../../api/knowledgeDocumentApi'
import type { PageResponse } from '../../../utils/page'
import { formatDateTime } from '../../../utils/format'
import Modal from '../../Modal'
import { ErrorState, LoadingState } from '../../StateBlock'
import { formatBytes } from './KnowledgeDetailsContent'

type KnowledgeVersionsDialogProps = {
    versionDocument: KnowledgeDocument
    versionsPage: PageResponse<KnowledgeDocumentVersion> | null
    versionsLoading: boolean
    versionsError: string
    openingVersionId: string
    downloadingVersionId: string
    onClose: () => void
    onOpenVersion: (document: KnowledgeDocument, version: KnowledgeDocumentVersion) => void | Promise<void>
    onDownloadVersion: (document: KnowledgeDocument, version: KnowledgeDocumentVersion) => void | Promise<void>
}

export function KnowledgeVersionsDialog({
    versionDocument,
    versionsPage,
    versionsLoading,
    versionsError,
    openingVersionId,
    downloadingVersionId,
    onClose,
    onOpenVersion,
    onDownloadVersion,
}: KnowledgeVersionsDialogProps) {
    const openDocumentVersionPreview = onOpenVersion
    const downloadDocumentVersion = onDownloadVersion
    return (
                <Modal
                    title={`Версии: ${versionDocument.name}`}
                    size="lg"
                    onClose={onClose}
                >
                    <div className="knowledge-upload-form">
                        <p className="knowledge-upload-hint">
                            Версии неизменяемы. Откройте или скачайте именно ту версию,
                            которая указана в citation или Answer Passport.
                        </p>

                        {versionsLoading && (
                            <LoadingState message="Загрузка истории версий..." />
                        )}

                        {!versionsLoading && versionsError && (
                            <ErrorState message={versionsError} />
                        )}

                        {!versionsLoading && !versionsError && versionsPage && (
                            <div className="table-wrapper knowledge-documents-scroll">
                                <table className="knowledge-documents">
                                    <thead>
                                        <tr>
                                            <th>Версия</th>
                                            <th>Файл</th>
                                            <th>Создана</th>
                                            <th>Действия</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {versionsPage.content.map((version) => (
                                            <tr key={version.id}>
                                                <td>
                                                    <strong>v{version.versionNumber}</strong>
                                                    {version.id === versionDocument.currentVersionId && (
                                                        <span className="knowledge-document-version">Текущая</span>
                                                    )}
                                                </td>
                                                <td>
                                                    <div className="knowledge-document-name">
                                                        <strong>{version.originalFilename}</strong>
                                                        <small>{formatBytes(version.sizeBytes)} · SHA-256: {version.sha256}</small>
                                                    </div>
                                                </td>
                                                <td>{formatDateTime(version.createdAt)}</td>
                                                <td>
                                                    <div className="document-actions">
                                                        <button
                                                            type="button"
                                                            className="secondary-button"
                                                            disabled={openingVersionId === version.id || downloadingVersionId === version.id}
                                                            onClick={() => void openDocumentVersionPreview(versionDocument, version)}
                                                        >
                                                            {openingVersionId === version.id ? 'Открываем…' : 'Открыть'}
                                                        </button>
                                                        <button
                                                            type="button"
                                                            className="secondary-button"
                                                            disabled={openingVersionId === version.id || downloadingVersionId === version.id}
                                                            onClick={() => void downloadDocumentVersion(versionDocument, version)}
                                                        >
                                                            {downloadingVersionId === version.id ? 'Скачиваем…' : 'Скачать'}
                                                        </button>
                                                    </div>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </div>
                </Modal>
    )
}
