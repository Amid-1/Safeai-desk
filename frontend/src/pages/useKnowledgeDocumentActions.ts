import {
    useRef,
    useState,
} from 'react'
import {
    fetchKnowledgeDocumentBlob,
    reindexKnowledgeDocument,
} from '../api/knowledgeDocumentApi'
import type {
    KnowledgeDocument,
} from '../api/knowledgeDocumentApi'
import {
    getApiErrorMessage,
} from '../api/http'

type UseKnowledgeDocumentActionsOptions = {
    knowledgeBaseId: string
    canEditDocuments: boolean
    documentPage: number
    setError: (message: string) => void
    loadOverview: () => Promise<void>
    loadDocuments: (page: number) => Promise<void>
}

export function useKnowledgeDocumentActions({
    knowledgeBaseId,
    canEditDocuments,
    documentPage,
    setError,
    loadOverview,
    loadDocuments,
}: UseKnowledgeDocumentActionsOptions) {
    const [
        reindexingDocumentId,
        setReindexingDocumentId,
    ] = useState('')
    const reindexInFlightRef = useRef(false)
    const [reindexNotice, setReindexNotice] = useState('')
    const [
        openingDocumentId,
        setOpeningDocumentId,
    ] = useState('')
    const [
        downloadingDocumentId,
        setDownloadingDocumentId,
    ] = useState('')

    async function openDocumentPreview(
        knowledgeDocument: KnowledgeDocument,
    ) {
        if (
            openingDocumentId
            || downloadingDocumentId
        ) {
            return
        }

        const previewWindow = window.open(
            'about:blank',
            '_blank',
        )

        if (!previewWindow) {
            setError(
                'Браузер заблокировал новую вкладку. Разрешите всплывающие окна для SafeAI Desk и повторите попытку.',
            )
            return
        }

        try {
            previewWindow.opener = null
            previewWindow.document.title =
                'SafeAI Desk — загрузка документа'

            if (previewWindow.document.body) {
                previewWindow.document.body.textContent =
                    'Загрузка документа...'
            }
        } catch {
            // about:blank может быть недоступен отдельным browser implementation.
        }

        setOpeningDocumentId(
            knowledgeDocument.id,
        )
        setError('')

        try {
            const blob =
                await fetchKnowledgeDocumentBlob(
                    knowledgeBaseId,
                    knowledgeDocument.id,
                )

            const objectUrl =
                URL.createObjectURL(blob)

            previewWindow.location.replace(
                objectUrl,
            )

            window.setTimeout(
                () => {
                    URL.revokeObjectURL(objectUrl)
                },
                60 * 60 * 1000,
            )
        } catch (previewFailure) {
            previewWindow.close()
            setError(
                getApiErrorMessage(
                    previewFailure,
                    'Не удалось открыть документ.',
                ),
            )
        } finally {
            setOpeningDocumentId('')
        }
    }

    async function downloadDocumentFile(
        knowledgeDocument: KnowledgeDocument,
    ) {
        if (
            downloadingDocumentId
            || openingDocumentId
        ) {
            return
        }

        setDownloadingDocumentId(
            knowledgeDocument.id,
        )
        setError('')

        try {
            const blob =
                await fetchKnowledgeDocumentBlob(
                    knowledgeBaseId,
                    knowledgeDocument.id,
                )

            const objectUrl =
                URL.createObjectURL(blob)
            const link =
                window.document.createElement('a')

            link.href = objectUrl
            link.download =
                knowledgeDocument.originalFilename
                ?? knowledgeDocument.name
            link.rel = 'noopener'

            window.document.body.appendChild(link)
            link.click()
            link.remove()

            window.setTimeout(
                () => {
                    URL.revokeObjectURL(objectUrl)
                },
                1_000,
            )
        } catch (downloadFailure) {
            setError(
                getApiErrorMessage(
                    downloadFailure,
                    'Не удалось скачать документ.',
                ),
            )
        } finally {
            setDownloadingDocumentId('')
        }
    }

    async function requestReindex(
        document: KnowledgeDocument,
    ) {
        if (
            reindexInFlightRef.current
            || !canEditDocuments
            || !document.currentVersionId
            || document.status === 'PENDING'
            || document.status === 'VALIDATING'
            || document.status === 'EXTRACTING'
            || document.status === 'CHUNKING'
        ) {
            return
        }

        reindexInFlightRef.current = true
        setReindexingDocumentId(document.id)
        setReindexNotice('')
        setError('')

        try {
            await reindexKnowledgeDocument(
                knowledgeBaseId,
                document.id,
            )
            setReindexNotice(
                `Переиндексация «${document.name}» поставлена в очередь. `
                + 'Статусы обновляются после загрузки данных с сервера.',
            )
            const refresh = await Promise.allSettled([
                loadOverview(),
                loadDocuments(documentPage),
            ])
            if (refresh.some((result) => result.status === 'rejected')) {
                setError(
                    'Переиндексация принята сервером, но обновить состояние '
                    + 'не удалось. Обновите список документов. '
                    + 'Не запускайте операцию повторно без проверки статуса.',
                )
            }
        } catch (reindexFailure) {
            setError(
                getApiErrorMessage(
                    reindexFailure,
                    'Не удалось запустить переиндексацию документа.',
                ),
            )
        } finally {
            reindexInFlightRef.current = false
            setReindexingDocumentId('')
        }
    }

    return {
        reindexingDocumentId,
        reindexNotice,
        openingDocumentId,
        downloadingDocumentId,
        openDocumentPreview,
        downloadDocumentFile,
        requestReindex,
    }
}
