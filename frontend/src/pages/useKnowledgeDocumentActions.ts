import {
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
    baseEnabled: boolean
    documentPage: number
    setError: (message: string) => void
    loadOverview: () => Promise<void>
    loadDocuments: (page: number) => Promise<void>
}

export function useKnowledgeDocumentActions({
    knowledgeBaseId,
    baseEnabled,
    documentPage,
    setError,
    loadOverview,
    loadDocuments,
}: UseKnowledgeDocumentActionsOptions) {
    const [
        reindexingDocumentId,
        setReindexingDocumentId,
    ] = useState('')
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
            reindexingDocumentId
            || !baseEnabled
        ) {
            return
        }

        setReindexingDocumentId(document.id)
        setError('')

        try {
            await reindexKnowledgeDocument(
                knowledgeBaseId,
                document.id,
            )
            await Promise.all([
                loadOverview(),
                loadDocuments(documentPage),
            ])
        } catch (reindexFailure) {
            setError(
                getApiErrorMessage(
                    reindexFailure,
                    'Не удалось запустить переиндексацию документа.',
                ),
            )
        } finally {
            setReindexingDocumentId('')
        }
    }

    return {
        reindexingDocumentId,
        openingDocumentId,
        downloadingDocumentId,
        openDocumentPreview,
        downloadDocumentFile,
        requestReindex,
    }
}
