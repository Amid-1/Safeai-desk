import {
    useState,
} from 'react'
import {
    uploadKnowledgeDocument,
    uploadKnowledgeDocumentVersion,
} from '../api/knowledgeDocumentApi'
import type {
    KnowledgeDocument,
} from '../api/knowledgeDocumentApi'
import {
    getApiErrorPresentation,
} from '../api/http'

export const MAX_KNOWLEDGE_FILE_SIZE =
    25 * 1024 * 1024

type UploadTarget =
    | KnowledgeDocument
    | 'new'
    | null

type UseKnowledgeUploadOptions = {
    knowledgeBaseId: string
    documentPage: number
    setDocumentPage: (page: number) => void
    refreshAfterSuccessfulUpload:
        (page: number) => Promise<void>
}

export function useKnowledgeUploadController({
    knowledgeBaseId,
    documentPage,
    setDocumentPage,
    refreshAfterSuccessfulUpload,
}: UseKnowledgeUploadOptions) {
    const [busy, setBusy] =
        useState(false)
    const [uploadTarget, setUploadTarget] =
        useState<UploadTarget>(null)
    const [uploadName, setUploadName] =
        useState('')
    const [uploadFile, setUploadFile] =
        useState<File | null>(null)
    const [uploadError, setUploadError] =
        useState('')
    const [uploadRequestId, setUploadRequestId] =
        useState('')
    const [requestIdCopied, setRequestIdCopied] =
        useState(false)

    function resetUploadError() {
        setUploadError('')
        setUploadRequestId('')
        setRequestIdCopied(false)
    }

    function openUpload(
        target: Exclude<UploadTarget, null>,
    ) {
        setUploadTarget(target)
        setUploadFile(null)
        resetUploadError()
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
        resetUploadError()
    }

    function changeUploadName(value: string) {
        setUploadName(value)

        if (uploadError) {
            resetUploadError()
        }
    }

    function selectFile(file: File | null) {
        resetUploadError()
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
            && file.size
                > MAX_KNOWLEDGE_FILE_SIZE
        ) {
            setUploadError(
                'Размер файла превышает 25 МБ.',
            )
        }
    }

    async function submitUpload() {
        if (!uploadFile || !uploadTarget) {
            setUploadError('Выберите файл.')
            return
        }

        if (
            uploadFile.size
            > MAX_KNOWLEDGE_FILE_SIZE
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
        resetUploadError()

        const submittedTarget = uploadTarget

        try {
            if (submittedTarget === 'new') {
                await uploadKnowledgeDocument(
                    knowledgeBaseId,
                    uploadFile,
                    uploadName,
                )
            } else {
                await uploadKnowledgeDocumentVersion(
                    knowledgeBaseId,
                    submittedTarget.id,
                    uploadFile,
                )
            }
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
                presentation.requestId ?? '',
            )
            setRequestIdCopied(false)
            setBusy(false)
            return
        }

        const targetPage =
            submittedTarget === 'new'
                ? 0
                : documentPage

        if (submittedTarget === 'new') {
            setDocumentPage(0)
        }

        setUploadTarget(null)
        setUploadFile(null)
        setUploadName('')
        setBusy(false)

        await refreshAfterSuccessfulUpload(
            targetPage,
        )
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

    const uploadSubmitDisabled =
        busy
        || !uploadFile
        || uploadFile.size
            > MAX_KNOWLEDGE_FILE_SIZE
        || (
            uploadTarget === 'new'
            && !uploadName.trim()
        )

    return {
        busy,
        uploadTarget,
        uploadName,
        uploadFile,
        uploadError,
        uploadRequestId,
        requestIdCopied,
        uploadSubmitDisabled,
        openUpload,
        closeUpload,
        changeUploadName,
        selectFile,
        submitUpload,
        copyUploadRequestId,
    }
}
