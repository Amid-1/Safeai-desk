// frontend/src/features/knowledge/controller/useKnowledgeDeletionController.ts
import {useEffect, useRef, useState} from 'react'
import type {KnowledgeDocument} from '../../../entities/knowledge/api/knowledgeDocumentApi'
import {
    acceptDocumentDeletion, deletionCapability, listDocumentDeletions,
} from '../../../entities/knowledge/api/knowledgeDocumentDeletionApi'
import type {KnowledgeDocumentDeletion} from '../../../entities/knowledge/api/knowledgeDocumentDeletionApi'
import {getApiErrorMessage} from '../../../shared/api/http'
import {createSecureUuid} from '../../../shared/lib/secureUuid'

type Target = {document: KnowledgeDocument; key: string}
type CapabilityStatus = 'checking' | 'enabled' | 'disabled' | 'error'

const UNAVAILABLE_MESSAGES: Record<Exclude<CapabilityStatus, 'enabled'>, string> = {
    checking: 'Проверяем, доступно ли удаление документов…',
    disabled: 'Удаление документов отключено в настройках системы. '
        + 'Документы и файлы остаются на месте.',
    error: 'Не удалось проверить доступность удаления. '
        + 'Обновите страницу или обратитесь к администратору системы.',
}

export function useKnowledgeDeletionController(options: {
    knowledgeBaseId: string
    isOwner: boolean
    setError: (message: string) => void
    refresh: () => Promise<unknown>
}) {
    const {knowledgeBaseId, isOwner, setError, refresh} = options
    const [capabilityStatus, setCapabilityStatus] = useState<CapabilityStatus>('checking')
    const [target, setTarget] = useState<Target | null>(null)
    const [busy, setBusy] = useState(false)
    const inFlight = useRef(false)
    const [operations, setOperations] = useState<KnowledgeDocumentDeletion[]>([])
    const [validatedBaseId, setValidatedBaseId] = useState('')
    const available = isOwner && capabilityStatus === 'enabled'
        && validatedBaseId === knowledgeBaseId

    // История операций доступна отдельно от разрешения на новые удаления.
    // Ошибка загрузки истории не означает отсутствия ранее принятых операций.
    const [historyError, setHistoryError] = useState(false)

    useEffect(() => {
        if (!isOwner || !knowledgeBaseId) {
            setCapabilityStatus('disabled')
            setValidatedBaseId('')
            setOperations([])
            setHistoryError(false)
            setTarget(null)
            return
        }

        const controller = new AbortController()
        setCapabilityStatus('checking')
        setValidatedBaseId('')
        setHistoryError(false)
        setOperations([]) // Не показываем историю другой базы при переключении.
        setTarget(null)

        const load = async () => {
            // Проверяем историю даже при запрете удаления или ошибке проверки доступности.
            const [capability, history] = await Promise.allSettled([
                deletionCapability(knowledgeBaseId, controller.signal),
                listDocumentDeletions(knowledgeBaseId, controller.signal),
            ])
            if (controller.signal.aborted) return

            if (history.status === 'fulfilled') {
                setOperations(history.value)
                setHistoryError(false)
            } else {
                setOperations([])
                setHistoryError(true)
            }

            // Для удаления нужны и разрешение сервера, и успешно загруженная история.
            if (capability.status === 'rejected' || history.status === 'rejected') {
                setCapabilityStatus('error')
                setValidatedBaseId('')
            } else {
                setValidatedBaseId(capability.value ? knowledgeBaseId : '')
                setCapabilityStatus(capability.value ? 'enabled' : 'disabled')
            }
        }
        void load()
        return () => controller.abort()
    }, [knowledgeBaseId, isOwner])

    useEffect(() => {
        if (!isOwner || !knowledgeBaseId) return
        const controller = new AbortController()
        // Продолжаем читать историю при выключенном удалении.
        // Не повторяем запрос проверки доступности при каждом опросе.
        const handle = window.setInterval(() => {
            void listDocumentDeletions(knowledgeBaseId, controller.signal)
                .then(active => {
                    if (controller.signal.aborted) return
                    setOperations(active)
                    setHistoryError(false)
                })
                .catch(() => {
                    if (controller.signal.aborted) return
                    // При ошибке не считаем старую историю достоверной и запрещаем удаление.
                    setHistoryError(true)
                    setOperations([])
                    setCapabilityStatus('error')
                    setValidatedBaseId('')
                })
        }, 12_000)
        return () => { controller.abort(); window.clearInterval(handle) }
    }, [isOwner, knowledgeBaseId])

    function requestDeletion(document: KnowledgeDocument) {
        if (!available || inFlight.current) return
        setTarget({document, key: createSecureUuid()})
    }

    function cancelDeletion() {
        if (!inFlight.current) setTarget(null)
    }

    async function confirmDeletion() {
        if (!target || !available || inFlight.current) return
        inFlight.current = true
        setBusy(true)
        setError('')
        try {
            // Повторный запрос после таймаута использует тот же ключ идемпотентности.
            const accepted = await acceptDocumentDeletion(knowledgeBaseId,
                target.document.id, target.document.version, target.key)
            setOperations(current => [accepted, ...current.filter(o => o.operationId !== accepted.operationId)])
            setTarget(null)
            try {
                await refresh()
            } catch {
                setError('Удаление принято сервером, но список документов не обновился. Обновите страницу.')
            }
        } catch (error) {
            setError(getApiErrorMessage(error,
                'Не удалось подтвердить удаление. Проверьте статус, прежде чем повторять запрос.'))
            // Сохраняем окно и ключ запроса до выяснения состояния операции.
            throw error
        } finally {
            inFlight.current = false
            setBusy(false)
        }
    }

    const unavailableReason = available
        ? ''
        : capabilityStatus === 'enabled'
            ? UNAVAILABLE_MESSAGES.checking
            : UNAVAILABLE_MESSAGES[capabilityStatus]

    return {
        available,
        canShowAction: isOwner,
        unavailableReason,
        target, busy, operations, historyError, requestDeletion, cancelDeletion, confirmDeletion,
    }
}
