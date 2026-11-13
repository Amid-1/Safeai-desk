// frontend/src/features/knowledge/controller/useKnowledgeUploadController.ts
import {
    useRef,
    useState,
} from 'react'
import {
    uploadKnowledgeDocument,
    uploadKnowledgeDocumentVersion,
} from '../../../../entities/knowledge/api/knowledgeDocumentApi'
import type {
    KnowledgeDocument,
} from '../../../../entities/knowledge/api/knowledgeDocumentApi'
import {
    ApiError,
    getApiErrorPresentation,
} from '../../../../shared/api/http'

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
        (page: number, confirmed?: boolean) => Promise<void>
}

export function useKnowledgeUploadController({
    knowledgeBaseId,
    documentPage,
    setDocumentPage,
    refreshAfterSuccessfulUpload,
}: UseKnowledgeUploadOptions) {
    const [busy, setBusy] =
        useState(false)
    // Transport/5xx errors do not prove the POST was rolled back.
    const [uploadOutcomeUncertain, setUploadOutcomeUncertain] =
        useState(false)
    const uploadInFlightRef = useRef(false)
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
        setUploadOutcomeUncertain(false)
        setUploadFile(null)
        resetUploadError()
        setUploadName(
            target === 'new'
                ? ''
                : target.name,
        )
    }

    function closeUpload() {
        if (busy || uploadInFlightRef.current) {
            return
        }

        setUploadTarget(null)
        setUploadOutcomeUncertain(false)
        setUploadFile(null)
        resetUploadError()
    }

    function changeUploadName(value: string) {
        setUploadName(value)

        if (uploadError && !uploadOutcomeUncertain) {
            resetUploadError()
        }
    }

    function selectFile(file: File | null) {
        if (!uploadOutcomeUncertain) {
            resetUploadError()
        }
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
        if (uploadInFlightRef.current || uploadOutcomeUncertain) return

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

        uploadInFlightRef.current = true
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

            // 4xx validation/conflict is definitive, except timeout (408).
            // For transport errors, malformed success responses or 5xx,
            // a document might already have been committed by the server.
            const outcomeUncertain =
                !(uploadFailure instanceof ApiError)
                || uploadFailure.status === 0
                || uploadFailure.status === 408
                || uploadFailure.status >= 500

            if (outcomeUncertain) {
                const targetPage =
                    submittedTarget === 'new'
                        ? 0
                        : documentPage

                if (submittedTarget === 'new') {
                    setDocumentPage(0)
                }

                // Read only. Never repeat the non-idempotent POST automatically.
                // The list may still lag behind the committed operation.
                try {
                    await refreshAfterSuccessfulUpload(targetPage, false)
                } catch {
                    // Preserve the original upload uncertainty.
                }

                setUploadOutcomeUncertain(true)
                setUploadError(
                    'Не удалось подтвердить результат загрузки. '
                    + 'Документ мог сохраниться на сервере. '
                    + 'Закройте окно, проверьте обновлённый список '
                    + 'документов и при необходимости обновите страницу. '
                    + 'Не отправляйте тот же файл повторно до проверки.',
                )
            } else {
                setUploadError(presentation.message)
            }

            setUploadRequestId(presentation.requestId ?? '')
            setRequestIdCopied(false)
            uploadInFlightRef.current = false
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

        try {
            await refreshAfterSuccessfulUpload(targetPage)
        } finally {
            uploadInFlightRef.current = false
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

    const uploadSubmitDisabled =
        busy
        || uploadOutcomeUncertain
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
