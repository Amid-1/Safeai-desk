import type { KnowledgeDocument } from '../../../api/knowledgeDocumentApi'
import Modal from '../../Modal'
import { formatBytes } from './KnowledgeDetailsContent'

const ACCEPT =
    '.pdf,.docx,.txt,.html,.htm,.md,.csv,.xlsx,.pptx,.json,.xml,'
    + 'application/pdf,'
    + 'application/vnd.openxmlformats-officedocument.wordprocessingml.document,'
    + 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,'
    + 'application/vnd.openxmlformats-officedocument.presentationml.presentation,'
    + 'application/json,application/xml,text/xml,'
    + 'text/plain,text/html,text/markdown,text/csv'

type KnowledgeUploadDialogProps = {
    uploadTarget: KnowledgeDocument | 'new'
    uploadName: string
    busy: boolean
    uploadFile: File | null
    uploadError: string
    uploadRequestId: string
    requestIdCopied: boolean
    uploadSubmitDisabled: boolean
    onClose: () => void
    onUploadNameChange: (value: string) => void
    onFileSelect: (file: File | null) => void
    onCopyRequestId: () => void | Promise<void>
    onSubmit: () => void | Promise<void>
}

export function KnowledgeUploadDialog({
    uploadTarget,
    uploadName,
    busy,
    uploadFile,
    uploadError,
    uploadRequestId,
    requestIdCopied,
    uploadSubmitDisabled,
    onClose,
    onUploadNameChange,
    onFileSelect,
    onCopyRequestId,
    onSubmit,
}: KnowledgeUploadDialogProps) {
    const closeUpload = onClose
    const changeUploadName = onUploadNameChange
    const selectFile = onFileSelect
    const copyUploadRequestId = onCopyRequestId
    const submitUpload = onSubmit
    return (
                <Modal
                    title={
                        uploadTarget === 'new'
                            ? 'Загрузка документа'
                            : 'Новая версия документа'
                    }
                    onClose={
                        closeUpload
                    }
                >
                    <div className="knowledge-upload-form">
                        {uploadTarget === 'new' && (
                            <label>
                                Название документа

                                <input
                                    value={uploadName}
                                    maxLength={255}
                                    disabled={busy}
                                    autoComplete="off"
                                    onChange={(event) =>
                                        changeUploadName(
                                            event.target.value,
                                        )
                                    }
                                />
                            </label>
                        )}

                        {uploadTarget !== 'new' && (
                            <p className="knowledge-upload-target">
                                <strong>
                                    {
                                        uploadTarget.name
                                    }
                                </strong>

                                <span>
                                    Будет создана версия
                                    {' '}
                                    {
                                        (
                                            uploadTarget.versionNumber
                                            ?? 0
                                        ) + 1
                                    }
                                    . Предыдущая версия сохранится.
                                </span>
                            </p>
                        )}

                        <label className="knowledge-file-picker">
                            Файл

                            <input
                                type="file"
                                accept={ACCEPT}
                                disabled={busy}
                                onChange={(event) =>
                                    selectFile(
                                        event.target.files?.[0]
                                        ?? null,
                                    )
                                }
                            />
                        </label>

                        <p className="knowledge-upload-hint">
                            PDF, DOCX, TXT, HTML, MD, CSV, XLSX, PPTX, JSON или XML, не более 25 МБ.
                            Формат и структура проверяются backend по фактическому содержимому файла.
                        </p>

                        {uploadFile && (
                            <div className="knowledge-selected-file">
                                <div>
                                    <strong>
                                        {
                                            uploadFile.name
                                        }
                                    </strong>

                                    <span>
                                        Файл выбран
                                    </span>
                                </div>

                                <strong className="knowledge-selected-file__size">
                                    {
                                        formatBytes(
                                            uploadFile.size,
                                        )
                                    }
                                </strong>
                            </div>
                        )}

                        {uploadError && (
                            <div
                                className="error knowledge-upload-error"
                                role="alert"
                                aria-live="assertive"
                            >
                                <div className="knowledge-upload-error__message">
                                    {uploadError}
                                </div>

                                {uploadRequestId && (
                                    <div className="knowledge-upload-error__request">
                                        <span>
                                            Код запроса:
                                        </span>

                                        <code>
                                            {
                                                uploadRequestId
                                            }
                                        </code>

                                        <button
                                            type="button"
                                            className="knowledge-upload-error__copy"
                                            onClick={() =>
                                                void copyUploadRequestId()
                                            }
                                        >
                                            {
                                                requestIdCopied
                                                    ? 'Скопировано'
                                                    : 'Скопировать'
                                            }
                                        </button>
                                    </div>
                                )}
                            </div>
                        )}

                        <div className="knowledge-upload-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                disabled={busy}
                                onClick={
                                    closeUpload
                                }
                            >
                                Отмена
                            </button>

                            <button
                                type="button"
                                disabled={
                                    uploadSubmitDisabled
                                }
                                onClick={() =>
                                    void submitUpload()
                                }
                            >
                                {
                                    busy
                                        ? 'Загрузка...'
                                        : 'Загрузить'
                                }
                            </button>
                        </div>
                    </div>
                </Modal>
    )
}
