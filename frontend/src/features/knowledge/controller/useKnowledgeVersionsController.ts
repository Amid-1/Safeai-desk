// frontend/src/features/knowledge/controller/useKnowledgeVersionsController.ts
import {
    useEffect,
    useRef,
    useState,
} from 'react'
import {
    fetchKnowledgeDocumentVersionBlob,
    getKnowledgeDocumentVersions,
} from '../../../entities/knowledge/api/knowledgeDocumentApi'
import type {
    KnowledgeDocument,
    KnowledgeDocumentVersion,
} from '../../../entities/knowledge/api/knowledgeDocumentApi'
import {
    getApiErrorMessage,
} from '../../../shared/api/http'
import { downloadKnowledgeBlob, previewOrDownloadKnowledgeBlob } from '../lib/knowledgeFileTransfer'
import type {
    PageResponse,
} from '../../../shared/lib/page'
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
            await previewOrDownloadKnowledgeBlob(
                blob,
                version.originalFilename,
                previewWindow,
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
            downloadKnowledgeBlob(blob, version.originalFilename)
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
