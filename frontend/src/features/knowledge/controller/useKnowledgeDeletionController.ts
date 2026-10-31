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
    checking: 'Проверяем разрешение сервера на безвозвратное удаление.',
    disabled: 'Безвозвратное удаление пока не включено на сервере.',
    error: 'Не удалось проверить возможность удаления. Обновите страницу или проверьте backend.',
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

    // Reading deletion evidence is independent from the destructive feature flag.
    // Never infer "no operations" from a disabled capability or a failed GET.
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
        setOperations([]) // Prevent showing another base's history while switching.
        setTarget(null)

        const load = async () => {
            // GET must be attempted even if DELETE is disabled or capability fails.
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

            // A writable UI additionally requires a healthy status API.
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
        // Poll GET even while DELETE is disabled. Never poll the capability
        // route (conditional DELETE mappings may otherwise produce 404 floods).
        const handle = window.setInterval(() => {
            void listDocumentDeletions(knowledgeBaseId, controller.signal)
                .then(active => {
                    if (controller.signal.aborted) return
                    setOperations(active)
                    setHistoryError(false)
                })
                .catch(() => {
                    if (controller.signal.aborted) return
                    // Fail closed, but never render stale history as authoritative.
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
            // Reuse the same Idempotency-Key if timeout makes the result ambiguous.
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
            // Keep modal and idempotency key intact until the server state is known.
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
