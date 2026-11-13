// frontend/src/features/knowledge/controller/useKnowledgeDocumentActions.ts
import {
    useRef,
    useState,
} from 'react'
import {
    fetchKnowledgeDocumentBlob,
    reindexKnowledgeDocument,
    updateKnowledgeDocument,
} from '../../../../entities/knowledge/api/knowledgeDocumentApi'
import type {
    KnowledgeDocument,
} from '../../../../entities/knowledge/api/knowledgeDocumentApi'
import {
    getApiErrorMessage,
} from '../../../../shared/api/http'
import { downloadKnowledgeBlob, previewOrDownloadKnowledgeBlob } from '../../../knowledge/lib/knowledgeFileTransfer'

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
    const [enabledTarget, setEnabledTarget] = useState<KnowledgeDocument | null>(null)
    const [changingEnabledId, setChangingEnabledId] = useState('')
    const enableMutationInFlightRef = useRef(false)
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

            await previewOrDownloadKnowledgeBlob(
                blob,
                knowledgeDocument.originalFilename ?? knowledgeDocument.name,
                previewWindow,
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

            downloadKnowledgeBlob(
                blob,
                knowledgeDocument.originalFilename ?? knowledgeDocument.name,
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

    function requestEnabledChange(document: KnowledgeDocument) {
        if (!canEditDocuments || enableMutationInFlightRef.current) {
            return
        }
        setEnabledTarget(document)
    }

    function cancelEnabledChange() {
        if (!enableMutationInFlightRef.current) {
            setEnabledTarget(null)
        }
    }

    async function confirmEnabledChange() {
        const target = enabledTarget
        if (!canEditDocuments || !target || enableMutationInFlightRef.current) {
            return
        }

        enableMutationInFlightRef.current = true
        setChangingEnabledId(target.id)
        setError('')

        try {
            await updateKnowledgeDocument(knowledgeBaseId, target.id, {
                name: target.name,
                enabled: !target.enabled,
                expectedVersion: target.version,
            })
            // Success refers to acknowledged PATCH, not to subsequent GETs.
            setEnabledTarget(null)
            const refresh = await Promise.allSettled([
                loadOverview(),
                loadDocuments(documentPage),
            ])
            if (refresh.some((result) => result.status === 'rejected')) {
                setError(
                    'Сервер подтвердил изменение состояния документа, '
                    + 'но список не обновился. Обновите статусы вручную.',
                )
            }
        } catch (failure) {
            setError(
                getApiErrorMessage(
                    failure,
                    'Не удалось подтвердить изменение состояния. '
                    + 'Обновите список перед повторной попыткой.',
                ),
            )
            // ConfirmDialog must not close on an unconfirmed PATCH.
            throw failure
        } finally {
            enableMutationInFlightRef.current = false
            setChangingEnabledId('')
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
        enabledTarget,
        changingEnabledId,
        requestEnabledChange,
        cancelEnabledChange,
        confirmEnabledChange,
        reindexingDocumentId,
        reindexNotice,
        openingDocumentId,
        downloadingDocumentId,
        openDocumentPreview,
        downloadDocumentFile,
        requestReindex,
    }
}
