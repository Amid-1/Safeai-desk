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
    const available = isOwner && capabilityStatus === 'enabled'

    useEffect(() => {
        if (!isOwner || !knowledgeBaseId) {
            setCapabilityStatus('disabled')
            setOperations([])
            return
        }
        let alive = true
        const controller = new AbortController()
        setCapabilityStatus('checking')

        const check = async () => {
            try {
                const allowed = await deletionCapability(knowledgeBaseId, controller.signal)
                if (!alive) return
                if (!allowed) {
                    setCapabilityStatus('disabled')
                    setOperations([])
                    return
                }
                // Do not enable an irreversible UI mutation until the status API is usable.
                const active = await listDocumentDeletions(knowledgeBaseId, controller.signal)
                if (alive) {
                    setOperations(active)
                    setCapabilityStatus('enabled')
                }
            } catch {
                if (alive) {
                    setCapabilityStatus('error')
                    setOperations([])
                }
            }
        }
        void check()
        // The backend registers conditional DELETE mappings at startup. Recheck
        // capability on navigation/reload, not every 12s when disabled (404 flood).
        return () => { alive = false; controller.abort() }
    }, [knowledgeBaseId, isOwner])

    useEffect(() => {
        if (!available || !knowledgeBaseId) return
        let alive = true
        const controller = new AbortController()
        const handle = window.setInterval(() => {
            void listDocumentDeletions(knowledgeBaseId, controller.signal)
                .then(active => { if (alive) setOperations(active) })
                .catch(() => {
                    // Fail closed if the operation-status API becomes unavailable.
                    if (alive) setCapabilityStatus('error')
                })
        }, 12_000)
        return () => { alive = false; controller.abort(); window.clearInterval(handle) }
    }, [available, knowledgeBaseId])

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

    const unavailableReason = capabilityStatus === 'enabled'
        ? ''
        : UNAVAILABLE_MESSAGES[capabilityStatus]

    return {
        available,
        canShowAction: isOwner,
        unavailableReason,
        target, busy, operations, requestDeletion, cancelDeletion, confirmDeletion,
    }
}
