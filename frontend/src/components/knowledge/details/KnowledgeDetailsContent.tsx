import type { RefObject } from 'react'
import { Link } from 'react-router-dom'
import type { KnowledgeBase, KnowledgeBaseAccess } from '../../../api/knowledgeApi'
import type {
    KnowledgeDocument,
    KnowledgeHealth,
    KnowledgeIngestionStatus,
} from '../../../api/knowledgeDocumentApi'
import { formatDateTime } from '../../../utils/format'
import type { PageResponse } from '../../../utils/page'
import { EmptyState, ErrorState, LoadingState } from '../../StateBlock'
import ResizableScrollRegion from '../../ResizableScrollRegion'
import KnowledgePagination from '../KnowledgePagination'

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


export type KnowledgeDetailsContentProps = {
    base: KnowledgeBase
    health: KnowledgeHealth | null
    access: KnowledgeBaseAccess | null
    documents: KnowledgeDocument[]
    documentsPage: PageResponse<KnowledgeDocument>
    documentsLoading: boolean
    documentsError: string
    error: string
    documentsSectionRef: RefObject<HTMLElement | null>
    busy: boolean
    reindexingDocumentId: string
    openingDocumentId: string
    downloadingDocumentId: string
    setDocumentPage: (page: number) => void
    openUpload: (target: KnowledgeDocument | 'new') => void
    openDocumentPreview: (document: KnowledgeDocument) => void | Promise<void>
    downloadDocumentFile: (document: KnowledgeDocument) => void | Promise<void>
    openVersionHistory: (document: KnowledgeDocument) => void | Promise<void>
    requestReindex: (document: KnowledgeDocument) => void | Promise<void>
    onRetryOverview: () => void | Promise<void>
    onRetryDocuments: () => void | Promise<void>
}

export function KnowledgeDetailsContent({
    base,
    health,
    access,
    documents,
    documentsPage,
    documentsLoading,
    documentsError,
    error,
    documentsSectionRef,
    busy,
    reindexingDocumentId,
    openingDocumentId,
    downloadingDocumentId,
    setDocumentPage,
    openUpload,
    openDocumentPreview,
    downloadDocumentFile,
    openVersionHistory,
    requestReindex,
    onRetryOverview,
    onRetryDocuments,
}: KnowledgeDetailsContentProps) {
    return (
        <>
            <ResizableScrollRegion
                storageKey="safeai:knowledge-documents-height"
                label="список документов"
                upper={
                    <div className="knowledge-details__upper">
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

                                    {access && !access.canEditDocuments && (
                                        <span
                                            className="knowledge-details__base-state"
                                            title={`Уровень доступа: ${access.accessLevel}`}
                                        >
                                            Только чтение
                                        </span>
                                    )}
                                </div>
                            </div>

                            {access?.canEditDocuments && (
                                <button
                                    type="button"
                                    className="knowledge-details__upload-button"
                                    disabled={busy}
                                    onClick={() =>
                                        openUpload(
                                            'new',
                                        )
                                    }
                                >
                                    Загрузить документ
                                </button>
                            )}
                        </header>

                        {!base.enabled && (
                            <div className="knowledge-disabled-note">
                                <strong>
                                    База знаний отключена.
                                </strong>
                                <span>
                                    {access?.canEditDocuments
                                        ? 'Обычные пользователи не видят её. Вы можете подготовить документы перед повторным включением базы.'
                                        : 'База недоступна для обычной работы.'}
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
                                                void onRetryOverview()
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
                        </section>
                    </div>
                }
                footer={
                    !documentsLoading
                    && !documentsError
                    && documents.length > 0
                        ? (
                            <KnowledgePagination
                                page={documentsPage.page}
                                totalPages={documentsPage.totalPages}
                                totalElements={documentsPage.totalElements}
                                disabled={documentsLoading || busy}
                                ariaLabel="Пагинация документов базы знаний"
                                singlePageMessage="Все документы показаны"
                                onPageChange={setDocumentPage}
                            />
                        )
                        : null
                }
                lowerClassName="knowledge-documents-card"
                viewportClassName="table-wrapper knowledge-documents-scroll"
                defaultHeight={440}
                minHeight={72}
                maxHeight={760}
                minUpperHeight={128}
            >
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
                                        void onRetryDocuments()
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
                                message={
                                    access?.canEditDocuments
                                        ? 'Загрузите первый корпоративный документ. Поддерживаются PDF, DOCX, TXT, HTML, MD, CSV, XLSX, PPTX, JSON и XML до 25 МБ.'
                                        : 'В этой базе знаний пока нет доступных документов.'
                                }
                            />
                        </div>
                    )}

                {!documentsLoading
                    && !documentsError
                    && documents.length > 0
                    && (
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
                                                        <button
                                                            type="button"
                                                            className="knowledge-document-file-link"
                                                            disabled={
                                                                openingDocumentId === document.id
                                                                || downloadingDocumentId === document.id
                                                            }
                                                            aria-label={`Открыть файл ${document.originalFilename} в новой вкладке`}
                                                            title="Открыть текущую версию в новой вкладке"
                                                            onClick={() =>
                                                                void openDocumentPreview(
                                                                    document,
                                                                )
                                                            }
                                                        >
                                                            {
                                                                openingDocumentId === document.id
                                                                    ? 'Открываем…'
                                                                    : document.originalFilename
                                                            }
                                                        </button>
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
                                            <button
                                                type="button"
                                                className="secondary-button document-download-button"
                                                disabled={
                                                    downloadingDocumentId === document.id
                                                    || openingDocumentId === document.id
                                                }
                                                aria-label={`Скачать ${document.originalFilename ?? document.name}`}
                                                onClick={() =>
                                                    void downloadDocumentFile(
                                                        document,
                                                    )
                                                }
                                            >
                                                {
                                                    downloadingDocumentId === document.id
                                                        ? 'Скачиваем…'
                                                        : 'Скачать'
                                                }
                                            </button>

                                            {access?.canEditDocuments && (
                                                <>
                                                    <button
                                                        type="button"
                                                        className="secondary-button"
                                                        disabled={busy}
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
                                                        className="secondary-button"
                                                        onClick={() => void openVersionHistory(document)}
                                                    >
                                                        Версии
                                                    </button>

                                                    <button
                                                        type="button"
                                                        className="secondary-button document-reindex-button"
                                                        disabled={
                                                            busy
                                                            || reindexingDocumentId === document.id
                                                            || !document.currentVersionId
                                                        }
                                                        title="Повторно извлечь текст, создать chunks и embeddings для текущей версии"
                                                        onClick={() => void requestReindex(document)}
                                                    >
                                                        {reindexingDocumentId === document.id ? 'Запускаем…' : 'Переиндексировать'}
                                                    </button>
                                                </>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            )
                        },
                                                        )
                                                    }
                        </tbody>
                        </table>
                    )}
            </ResizableScrollRegion>
        </>
    )
}

function healthStateLabel(state: KnowledgeHealth['state']): string {
    switch (state) {
        case 'HEALTHY': return 'База готова к ответам'
        case 'INDEXING': return 'Документы подготавливаются'
        case 'DEGRADED': return 'Некоторые документы требуют внимания'
        case 'DISABLED': return 'База отключена'
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

export function formatBytes(
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

