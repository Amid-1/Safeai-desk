import {
    useEffect,
    useRef,
    useState,
} from 'react'
import {
    fetchKnowledgeDocumentVersionBlob,
    getKnowledgeDocumentVersions,
} from '../api/knowledgeDocumentApi'
import type {
    KnowledgeDocument,
    KnowledgeDocumentVersion,
} from '../api/knowledgeDocumentApi'
import {
    getApiErrorMessage,
} from '../api/http'
import type {
    PageResponse,
} from '../utils/page'
import {
    KNOWLEDGE_DOCUMENT_PAGE_SIZE,
} from './useKnowledgeDetailsData'

type UseKnowledgeVersionsOptions = {
    knowledgeBaseId: string
    setError: (message: string) => void
}

export function useKnowledgeVersionsController({
    knowledgeBaseId,
    setError,
}: UseKnowledgeVersionsOptions) {
    const [versionDocument, setVersionDocument] =
        useState<KnowledgeDocument | null>(null)
    const [versionsPage, setVersionsPage] =
        useState<PageResponse<KnowledgeDocumentVersion> | null>(
            null,
        )
    const [versionsLoading, setVersionsLoading] =
        useState(false)
    const [versionsError, setVersionsError] =
        useState('')
    const [openingVersionId, setOpeningVersionId] =
        useState('')
    const [
        downloadingVersionId,
        setDownloadingVersionId,
    ] = useState('')

    const versionLoadControllerRef =
        useRef<AbortController | null>(null)

    useEffect(() => {
        return () => {
            versionLoadControllerRef.current?.abort()
        }
    }, [])

    async function openVersionHistory(
        document: KnowledgeDocument,
    ) {
        versionLoadControllerRef.current?.abort()

        const controller =
            new AbortController()

        versionLoadControllerRef.current =
            controller
        setVersionsPage(null)
        setVersionsError('')
        setVersionsLoading(true)
        setVersionDocument(document)

        try {
            const response =
                await getKnowledgeDocumentVersions(
                    knowledgeBaseId,
                    document.id,
                    0,
                    KNOWLEDGE_DOCUMENT_PAGE_SIZE,
                    controller.signal,
                )

            if (!controller.signal.aborted) {
                setVersionsPage(response)
            }
        } catch (loadError) {
            if (!controller.signal.aborted) {
                setVersionsError(
                    getApiErrorMessage(
                        loadError,
                        'Не удалось загрузить историю версий.',
                    ),
                )
            }
        } finally {
            if (!controller.signal.aborted) {
                setVersionsLoading(false)
            }
        }
    }

    function closeVersionHistory() {
        versionLoadControllerRef.current?.abort()
        setVersionDocument(null)
        setVersionsPage(null)
        setVersionsError('')
    }

    async function openDocumentVersionPreview(
        document: KnowledgeDocument,
        version: KnowledgeDocumentVersion,
    ) {
        if (
            openingVersionId
            || downloadingVersionId
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

        setOpeningVersionId(version.id)
        setError('')

        try {
            previewWindow.opener = null
            const blob =
                await fetchKnowledgeDocumentVersionBlob(
                    knowledgeBaseId,
                    document.id,
                    version.id,
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
                    'Не удалось открыть версию документа.',
                ),
            )
        } finally {
            setOpeningVersionId('')
        }
    }

    async function downloadDocumentVersion(
        document: KnowledgeDocument,
        version: KnowledgeDocumentVersion,
    ) {
        if (
            openingVersionId
            || downloadingVersionId
        ) {
            return
        }

        setDownloadingVersionId(version.id)
        setError('')

        try {
            const blob =
                await fetchKnowledgeDocumentVersionBlob(
                    knowledgeBaseId,
                    document.id,
                    version.id,
                )
            const objectUrl =
                URL.createObjectURL(blob)
            const link =
                window.document.createElement('a')

            link.href = objectUrl
            link.download = version.originalFilename
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
                    'Не удалось скачать версию документа.',
                ),
            )
        } finally {
            setDownloadingVersionId('')
        }
    }

    return {
        versionDocument,
        versionsPage,
        versionsLoading,
        versionsError,
        openingVersionId,
        downloadingVersionId,
        openVersionHistory,
        closeVersionHistory,
        openDocumentVersionPreview,
        downloadDocumentVersion,
    }
}
