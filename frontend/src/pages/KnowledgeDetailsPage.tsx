import {
    useCallback,
    useEffect,
    useRef,
    useState,
} from 'react'
import {
    Link,
    useParams,
} from 'react-router-dom'
import {
    getKnowledgeBase,
} from '../api/knowledgeApi'
import type {
    KnowledgeBase,
} from '../api/knowledgeApi'
import {
    getKnowledgeDocuments,
    getKnowledgeHealth,
    knowledgeDocumentDownloadUrl,
    reindexKnowledgeDocument,
    uploadKnowledgeDocument,
    uploadKnowledgeDocumentVersion,
} from '../api/knowledgeDocumentApi'
import type {
    KnowledgeDocument,
    KnowledgeHealth,
    KnowledgeIngestionStatus,
} from '../api/knowledgeDocumentApi'
import {
    getApiErrorMessage,
    getApiErrorPresentation,
} from '../api/http'
import {
    formatDateTime,
} from '../utils/format'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../components/StateBlock'
import Modal from '../components/Modal'
import KnowledgePagination
    from '../components/knowledge/KnowledgePagination'
import type {
    PageResponse,
} from '../utils/page'
import './KnowledgeDetailsPage.css'

const MAX_FILE_SIZE =
    25 * 1024 * 1024

const DOCUMENT_PAGE_SIZE = 50

const EMPTY_DOCUMENT_PAGE:
    PageResponse<KnowledgeDocument> = {
    content: [],
    page: 0,
    size: DOCUMENT_PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
}

const ACCEPT =
    '.pdf,.docx,.txt,.html,.htm,.md,.csv,.xlsx,.pptx,.json,.xml,'
    + 'application/pdf,'
    + 'application/vnd.openxmlformats-officedocument.wordprocessingml.document,'
    + 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,'
    + 'application/vnd.openxmlformats-officedocument.presentationml.presentation,'
    + 'application/json,application/xml,text/xml,'
    + 'text/plain,text/html,text/markdown,text/csv'

const STATUS_LABEL:
    Record<KnowledgeIngestionStatus, string> = {
        PENDING: 'Ожидает обработки',
        VALIDATING: 'Проверяется',
        EXTRACTING: 'Извлекается текст',
        CHUNKING: 'Формируются фрагменты',
        READY: 'Готов',
        FAILED: 'Ошибка обработки',
    }

const STATUS_HINT:
    Record<KnowledgeIngestionStatus, string> = {
        PENDING:
            'Файл сохранён и ожидает запуска обработки.',
        VALIDATING:
            'Система выполняет дополнительную проверку документа.',
        EXTRACTING:
            'Из документа извлекается содержимое для базы знаний.',
        CHUNKING:
            'Документ разбивается на фрагменты для полнотекстового и смыслового поиска.',
        READY:
            'Документ обработан и готов к использованию.',
        FAILED:
            'Во время обработки документа произошла ошибка.',
    }

type UploadTarget =
    | KnowledgeDocument
    | 'new'
    | null

function KnowledgeDetailsPage() {
    const {
        knowledgeBaseId = '',
    } = useParams()

    const [
        base,
        setBase,
    ] = useState<KnowledgeBase | null>(
        null,
    )

    const [
        documentPage,
        setDocumentPage,
    ] = useState(0)

    const [
        documentsPage,
        setDocumentsPage,
    ] = useState<PageResponse<KnowledgeDocument>>(
        EMPTY_DOCUMENT_PAGE,
    )

    const [
        documentsLoading,
        setDocumentsLoading,
    ] = useState(true)

    const [
        documentsError,
        setDocumentsError,
    ] = useState('')

    const documents = documentsPage.content

    const [health, setHealth] = useState<KnowledgeHealth | null>(null)
    const [reindexingDocumentId, setReindexingDocumentId] = useState('')
    const documentsSectionRef = useRef<HTMLElement | null>(null)

    const [
        loading,
        setLoading,
    ] = useState(true)

    const [
        busy,
        setBusy,
    ] = useState(false)

    const [
        error,
        setError,
    ] = useState('')

    const [
        uploadTarget,
        setUploadTarget,
    ] = useState<UploadTarget>(
        null,
    )

    const [
        uploadName,
        setUploadName,
    ] = useState('')

    const [
        uploadFile,
        setUploadFile,
    ] = useState<File | null>(
        null,
    )

    const [
        uploadError,
        setUploadError,
    ] = useState('')

    const [
        uploadRequestId,
        setUploadRequestId,
    ] = useState('')

    const [
        requestIdCopied,
        setRequestIdCopied,
    ] = useState(false)

    const loadOverview =
        useCallback(
            async (
                signal?: AbortSignal,
            ) => {
                const [
                    knowledgeBase,
                    knowledgeHealth,
                ] = await Promise.all([
                    getKnowledgeBase(
                        knowledgeBaseId,
                        {
                            signal,
                        },
                    ),
                    getKnowledgeHealth(
                        knowledgeBaseId,
                        signal,
                    ),
                ])

                setBase(knowledgeBase)
                setHealth(knowledgeHealth)
            },
            [knowledgeBaseId],
        )

    const loadDocuments =
        useCallback(
            async (
                targetPage: number,
                signal?: AbortSignal,
            ) => {
                const response =
                    await getKnowledgeDocuments(
                        knowledgeBaseId,
                        targetPage,
                        DOCUMENT_PAGE_SIZE,
                        signal,
                    )

                if (
                    response.totalPages > 0
                    && targetPage
                    >= response.totalPages
                ) {
                    setDocumentPage(
                        response.totalPages - 1,
                    )
                    return
                }

                setDocumentsPage(response)
            },
            [knowledgeBaseId],
        )

    useEffect(() => {
        const controller =
            new AbortController()

        queueMicrotask(() => {
            if (controller.signal.aborted) {
                return
            }

            setLoading(true)
            setError('')

            void loadOverview(
                controller.signal,
            )
                .catch((loadError) => {
                    if (
                        !controller.signal.aborted
                    ) {
                        setError(
                            getApiErrorMessage(
                                loadError,
                                'Не удалось открыть базу знаний.',
                            ),
                        )
                    }
                })
                .finally(() => {
                    if (
                        !controller.signal.aborted
                    ) {
                        setLoading(false)
                    }
                })
        })

        return () =>
            controller.abort()
    }, [loadOverview])

    useEffect(() => {
        const controller =
            new AbortController()

        queueMicrotask(() => {
            if (controller.signal.aborted) {
                return
            }

            setDocumentsLoading(true)
            setDocumentsError('')

            void loadDocuments(
                documentPage,
                controller.signal,
            )
                .catch((loadError) => {
                    if (
                        !controller.signal.aborted
                    ) {
                        setDocumentsError(
                            getApiErrorMessage(
                                loadError,
                                'Не удалось загрузить документы базы знаний.',
                            ),
                        )
                    }
                })
                .finally(() => {
                    if (
                        !controller.signal.aborted
                    ) {
                        setDocumentsLoading(false)
                    }
                })
        })

        return () =>
            controller.abort()
    }, [
        documentPage,
        loadDocuments,
    ])

    function openUpload(
        target:
            Exclude<UploadTarget, null>,
    ) {
        setUploadTarget(target)
        setUploadFile(null)
        setUploadError('')
        setUploadRequestId('')
        setRequestIdCopied(false)
        setUploadName(
            target === 'new'
                ? ''
                : target.name,
        )
    }

    function closeUpload() {
        if (busy) {
            return
        }

        setUploadTarget(null)
        setUploadFile(null)
        setUploadError('')
        setUploadRequestId('')
        setRequestIdCopied(false)
    }

    function changeUploadName(
        value: string,
    ) {
        setUploadName(value)

        /*
         * Серверная ошибка (например, конфликт имени) не должна навсегда
         * блокировать повторную отправку после исправления названия.
         */
        if (uploadError) {
            setUploadError('')
            setUploadRequestId('')
            setRequestIdCopied(false)
        }
    }

    function selectFile(
        file: File | null,
    ) {
        setUploadError('')
        setUploadRequestId('')
        setRequestIdCopied(false)
        setUploadFile(file)

        if (
            file
            && uploadTarget === 'new'
            && !uploadName.trim()
        ) {
            setUploadName(
                file.name.replace(
                    /\.[^.]+$/,
                    '',
                ),
            )
        }

        if (
            file
            && file.size > MAX_FILE_SIZE
        ) {
            setUploadError(
                'Размер файла превышает 25 МБ.',
            )
        }
    }

    async function submitUpload() {
        if (
            !uploadFile
            || !uploadTarget
        ) {
            setUploadError(
                'Выберите файл.',
            )
            return
        }

        if (
            uploadFile.size
            > MAX_FILE_SIZE
        ) {
            setUploadError(
                'Размер файла превышает 25 МБ.',
            )
            return
        }

        if (
            uploadTarget === 'new'
            && !uploadName.trim()
        ) {
            setUploadError(
                'Введите название документа.',
            )
            return
        }

        setBusy(true)
        setUploadError('')
        setUploadRequestId('')
        setRequestIdCopied(false)

        try {
            if (
                uploadTarget === 'new'
            ) {
                await uploadKnowledgeDocument(
                    knowledgeBaseId,
                    uploadFile,
                    uploadName,
                )
            } else {
                await uploadKnowledgeDocumentVersion(
                    knowledgeBaseId,
                    uploadTarget.id,
                    uploadFile,
                )
            }

            const targetPage =
                uploadTarget === 'new'
                    ? 0
                    : documentPage

            if (uploadTarget === 'new') {
                setDocumentPage(0)
            }

            await Promise.all([
                loadOverview(),
                loadDocuments(targetPage),
            ])

            setUploadTarget(null)
            setUploadFile(null)
        } catch (uploadFailure) {
            const presentation =
                getApiErrorPresentation(
                    uploadFailure,
                    'Не удалось загрузить файл.',
                )

            setUploadError(
                presentation.message,
            )

            setUploadRequestId(
                presentation.requestId
                ?? '',
            )

            setRequestIdCopied(false)
        } finally {
            setBusy(false)
        }
    }

    async function copyUploadRequestId() {
        if (!uploadRequestId) {
            return
        }

        try {
            await navigator.clipboard.writeText(
                uploadRequestId,
            )

            setRequestIdCopied(true)
        } catch {
            setRequestIdCopied(false)
        }
    }

    async function requestReindex(document: KnowledgeDocument) {
        if (reindexingDocumentId || !base?.enabled) {
            return
        }
        setReindexingDocumentId(document.id)
        setError('')
        try {
            await reindexKnowledgeDocument(knowledgeBaseId, document.id)
            await Promise.all([
                loadOverview(),
                loadDocuments(documentPage),
            ])
        } catch (reindexFailure) {
            setError(getApiErrorMessage(
                reindexFailure,
                'Не удалось запустить переиндексацию документа.',
            ))
        } finally {
            setReindexingDocumentId('')
        }
    }

    if (loading) {
        return (
            <LoadingState
                message="Загрузка базы знаний..."
            />
        )
    }

    if (!base) {
        return (
            <ErrorState
                message={
                    error
                    || 'База знаний не найдена.'
                }
            />
        )
    }

    const uploadSubmitDisabled =
        busy
        || !uploadFile
        || uploadFile.size
            > MAX_FILE_SIZE
        || (
            uploadTarget === 'new'
            && !uploadName.trim()
        )

    return (
        <div className="page knowledge-details">
            <Link
                to="/knowledge"
                className="knowledge-details__back"
            >
                <span aria-hidden="true">
                    ←
                </span>
                {' '}
                Базы знаний
            </Link>

            <header className="knowledge-details__header">
                <div className="knowledge-details__heading">
                    <span className="knowledge-details__eyebrow">
                        База знаний
                    </span>

                    <h1>{base.name}</h1>

                    <p className="muted">
                        {
                            base.description
                            ?? 'Корпоративные документы и инструкции.'
                        }
                    </p>

                    <div className="knowledge-details__summary">
                        <button
                            type="button"
                            className="knowledge-details__documents-link"
                            onClick={() =>
                                documentsSectionRef.current?.scrollIntoView({
                                    behavior: 'smooth',
                                    block: 'start',
                                })
                            }
                        >
                            Документы
                            <strong>{documentsPage.totalElements}</strong>
                        </button>

                        <span
                            className={
                                base.enabled
                                    ? 'knowledge-details__base-state knowledge-details__base-state--enabled'
                                    : 'knowledge-details__base-state knowledge-details__base-state--disabled'
                            }
                        >
                            {
                                base.enabled
                                    ? 'База активна'
                                    : 'База отключена'
                            }
                        </span>
                    </div>
                </div>

                <button
                    type="button"
                    className="knowledge-details__upload-button"
                    disabled={
                        !base.enabled
                    }
                    onClick={() =>
                        openUpload(
                            'new',
                        )
                    }
                >
                    Загрузить документ
                </button>
            </header>

            {!base.enabled && (
                <div className="knowledge-disabled-note">
                    <strong>
                        База знаний отключена.
                    </strong>
                    <span>
                        Обычные пользователи не видят её и не могут читать документы.
                    </span>
                </div>
            )}

            {health && (
                <section
                    className={`knowledge-health knowledge-health--${health.state.toLowerCase()}`}
                    aria-label="Готовность базы знаний к ответам AI"
                >
                    <div>
                        <span className="knowledge-health__eyebrow">Готовность базы к поиску</span>
                        <strong>{healthStateLabel(health.state)}</strong>
                        <small>
                            {embeddingModelLabel(health.activeEmbeddingModel)}
                            {' · '}
                            <code>{health.activeEmbeddingModel}</code>
                        </small>
                        <p>
                            Документы подготовлены для полнотекстового и смыслового
                            поиска, который подбирает источники для ответа AI.
                        </p>
                    </div>
                    <dl>
                        <HealthMetric label="Готовы к поиску" value={`${health.searchableDocuments} из ${health.enabledDocuments}`} hint="документов" />
                        <HealthMetric label="Фрагменты для AI" value={health.activeChunks} hint="частей документов" />
                        <HealthMetric label="Обрабатываются" value={health.pendingDocuments + health.processingDocuments} hint="документов" />
                        <HealthMetric label="Ошибки обработки" value={health.failedDocuments + health.staleEmbeddingDocuments} hint="нужна проверка" />
                    </dl>
                </section>
            )}

            {error && (
                <div className="knowledge-details__notice">
                    <ErrorState
                        variant="inline"
                        message={error}
                        action={
                            <button
                                type="button"
                                onClick={() => {
                                    setError('')
                                    void Promise.all([
                                        loadOverview(),
                                        loadDocuments(documentPage),
                                    ]).catch((loadError) => {
                                        setError(
                                            getApiErrorMessage(
                                                loadError,
                                                'Не удалось обновить данные базы знаний.',
                                            ),
                                        )
                                    })
                                }}
                            >
                                Повторить
                            </button>
                        }
                    />
                </div>
            )}

            <section
                ref={documentsSectionRef}
                id="knowledge-documents"
                className="knowledge-documents-section"
                aria-labelledby="knowledge-documents-title"
            >
                    <div className="knowledge-documents-section__header">
                        <div>
                            <span>Содержимое базы</span>
                            <h2 id="knowledge-documents-title">Загруженные документы</h2>
                            <p>
                                Здесь видны текущие версии, готовность к поиску и
                                доступные действия с файлами.
                            </p>
                        </div>
                        <strong aria-label={`${documentsPage.totalElements} документов`}>
                            {documentsPage.totalElements}
                        </strong>
                    </div>

                    {documentsLoading && (
                        <div className="knowledge-documents-state">
                            <LoadingState
                                variant="inline"
                                message="Загрузка документов..."
                            />
                        </div>
                    )}

                    {!documentsLoading && documentsError && (
                        <div className="knowledge-documents-state">
                            <ErrorState
                                variant="inline"
                                message={documentsError}
                                action={
                                    <button
                                        type="button"
                                        onClick={() => {
                                            setDocumentsError('')
                                            setDocumentsLoading(true)
                                            void loadDocuments(documentPage)
                                                .catch((loadError) => {
                                                    setDocumentsError(
                                                        getApiErrorMessage(
                                                            loadError,
                                                            'Не удалось загрузить документы базы знаний.',
                                                        ),
                                                    )
                                                })
                                                .finally(() => {
                                                    setDocumentsLoading(false)
                                                })
                                        }}
                                    >
                                        Повторить
                                    </button>
                                }
                            />
                        </div>
                    )}

                    {!documentsLoading
                        && !documentsError
                        && documentsPage.totalElements === 0
                        && (
                            <div className="knowledge-documents-state">
                                <EmptyState
                                    variant="inline"
                                    title="Документов пока нет"
                                    message="Загрузите первый корпоративный документ. Поддерживаются PDF, DOCX, TXT, HTML, MD, CSV, XLSX, PPTX, JSON и XML до 25 МБ."
                                />
                            </div>
                        )}

                    {!documentsLoading
                        && !documentsError
                        && documents.length > 0
                        && (
                            <div className="knowledge-documents-card">
                                <div className="table-wrapper knowledge-documents-scroll">
                                    <table className="knowledge-documents">
                        <thead>
                            <tr>
                                <th>
                                    Название
                                </th>
                                <th>
                                    Версия файла
                                </th>
                                <th>
                                    Статус
                                </th>
                                <th>
                                    Размер
                                </th>
                                <th>
                                    Обновлён
                                </th>
                                <th>
                                    Действия
                                </th>
                            </tr>
                        </thead>

                        <tbody>
                            {
                                documents.map(
                                    (document) => {
                                        const status =
                                            document.status
                                            ?? 'PENDING'

                                        const downloadUrl =
                                            knowledgeDocumentDownloadUrl(
                                                knowledgeBaseId,
                                                document.id,
                                            )

                                        const fileType =
                                            documentTypeLabel(
                                                document,
                                            )

                                        return (
                                            <tr
                                                key={
                                                    document.id
                                                }
                                            >
                                                <td>
                                                    <div className="knowledge-document-name">
                                                        <div className="knowledge-document-name__title">
                                                            <strong>
                                                                {
                                                                    document.name
                                                                }
                                                            </strong>

                                                            {fileType && (
                                                                <span className={`knowledge-document-type knowledge-document-type--${fileType.toLowerCase()}`}>
                                                                    {fileType}
                                                                </span>
                                                            )}
                                                        </div>

                                                        {
                                                            document.originalFilename
                                                                ? (
                                                                    <a
                                                                        className="knowledge-document-file-link"
                                                                        href={downloadUrl}
                                                                        aria-label={`Скачать файл ${document.originalFilename}`}
                                                                        title="Скачать текущую версию файла"
                                                                    >
                                                                        {
                                                                            document.originalFilename
                                                                        }
                                                                    </a>
                                                                )
                                                                : (
                                                                    <span className="knowledge-document-file-missing">
                                                                        Имя файла недоступно
                                                                    </span>
                                                                )
                                                        }
                                                    </div>
                                                </td>

                                                <td>
                                                    <span className="knowledge-document-version">
                                                        {
                                                            document.versionNumber
                                                            ?? '—'
                                                        }
                                                    </span>
                                                </td>

                                                <td>
                                                    <span
                                                        className={`document-status document-status--${status.toLowerCase()}`}
                                                        title={
                                                            STATUS_HINT[
                                                                status
                                                            ]
                                                        }
                                                    >
                                                        {
                                                            STATUS_LABEL[
                                                                status
                                                            ]
                                                        }
                                                    </span>
                                                </td>

                                                <td>
                                                    <span className="knowledge-document-size">
                                                        {
                                                            formatBytes(
                                                                document.sizeBytes,
                                                            )
                                                        }
                                                    </span>
                                                </td>

                                                <td>
                                                    <span className="knowledge-document-date">
                                                        {
                                                            formatDateTime(
                                                                document.updatedAt,
                                                            )
                                                        }
                                                    </span>
                                                </td>

                                                <td>
                                                    <div className="document-actions">
                                                        <a
                                                            className="secondary-button document-download-button"
                                                            href={downloadUrl}
                                                            aria-label={`Скачать ${document.originalFilename ?? document.name}`}
                                                        >
                                                            Скачать
                                                        </a>

                                                        <button
                                                            type="button"
                                                            className="secondary-button"
                                                            disabled={
                                                                busy
                                                                || !base.enabled
                                                            }
                                                            onClick={() =>
                                                                openUpload(
                                                                    document,
                                                                )
                                                            }
                                                        >
                                                            Новая версия
                                                        </button>

                                                        <button
                                                            type="button"
                                                            className="secondary-button document-reindex-button"
                                                            disabled={
                                                                busy
                                                                || !base.enabled
                                                                || reindexingDocumentId === document.id
                                                                || !document.currentVersionId
                                                            }
                                                            title="Повторно извлечь текст, создать chunks и embeddings для текущей версии"
                                                            onClick={() => void requestReindex(document)}
                                                        >
                                                            {reindexingDocumentId === document.id ? 'Запускаем…' : 'Переиндексировать'}
                                                        </button>
                                                    </div>
                                                </td>
                                            </tr>
                                        )
                                    },
                                )
                            }
                                    </tbody>
                                    </table>
                                </div>

                                <KnowledgePagination
                                    page={documentsPage.page}
                                    totalPages={documentsPage.totalPages}
                                    totalElements={documentsPage.totalElements}
                                    disabled={documentsLoading || busy}
                                    ariaLabel="Пагинация документов базы знаний"
                                    singlePageMessage="Все документы показаны"
                                    onPageChange={setDocumentPage}
                                />
                            </div>
                        )}
            </section>

            {uploadTarget && (
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
            )}
        </div>
    )
}

function healthStateLabel(state: KnowledgeHealth['state']): string {
    switch (state) {
        case 'HEALTHY': return 'База готова к ответам'
        case 'INDEXING': return 'Документы подготавливаются'
        case 'DEGRADED': return 'Некоторые документы требуют внимания'
        default: return 'Документов пока нет'
    }
}

function embeddingModelLabel(model: string): string {
    return model === 'safeai-feature-hash-v1'
        ? 'Демонстрационная векторизация'
        : 'Модель смыслового поиска'
}

function HealthMetric({
    label,
    value,
    hint,
}: {
    label: string
    value: string | number
    hint?: string
}) {
    return (
        <div>
            <dt>{label}</dt>
            <dd>{value}</dd>
            {hint && <small>{hint}</small>}
        </div>
    )
}

function documentTypeLabel(
    document: KnowledgeDocument,
): string {
    switch (document.mediaType) {
        case 'application/pdf':
            return 'PDF'

        case 'application/vnd.openxmlformats-officedocument.wordprocessingml.document':
            return 'DOCX'

        case 'text/plain':
            return 'TXT'

        case 'text/html':
            return 'HTML'

        case 'text/markdown':
            return 'MD'

        case 'text/csv':
            return 'CSV'

        case 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet':
            return 'XLSX'

        case 'application/vnd.openxmlformats-officedocument.presentationml.presentation':
            return 'PPTX'

        case 'application/json':
            return 'JSON'

        case 'application/xml':
        case 'text/xml':
            return 'XML'

        default:
            break
    }

    const filename =
        document.originalFilename
        ?.toLowerCase()

    if (filename?.endsWith('.pdf')) {
        return 'PDF'
    }

    if (filename?.endsWith('.docx')) {
        return 'DOCX'
    }

    if (filename?.endsWith('.txt')) {
        return 'TXT'
    }

    if (
        filename?.endsWith('.html')
        || filename?.endsWith('.htm')
    ) {
        return 'HTML'
    }

    if (filename?.endsWith('.md')) {
        return 'MD'
    }

    if (filename?.endsWith('.csv')) {
        return 'CSV'
    }

    if (filename?.endsWith('.xlsx')) {
        return 'XLSX'
    }

    if (filename?.endsWith('.pptx')) {
        return 'PPTX'
    }

    if (filename?.endsWith('.json')) {
        return 'JSON'
    }

    if (filename?.endsWith('.xml')) {
        return 'XML'
    }

    return ''
}

function formatBytes(
    value: number,
): string {
    if (value < 1024) {
        return `${value} Б`
    }

    if (
        value < 1024 * 1024
    ) {
        return `${(
            value / 1024
        ).toFixed(1)} КБ`
    }

    return `${(
        value / 1024 / 1024
    ).toFixed(1)} МБ`
}

export default KnowledgeDetailsPage
