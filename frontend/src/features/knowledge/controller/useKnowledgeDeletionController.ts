// frontend/src/features/knowledge/controller/useKnowledgeDeletionController.ts
import {useEffect, useRef, useState} from 'react'
import type {KnowledgeDocument} from '../../../entities/knowledge/api/knowledgeDocumentApi'
import {
    acceptDocumentDeletion, deletionCapability, listDocumentDeletions,
} from '../../../entities/knowledge/api/knowledgeDocumentDeletionApi'
import type {KnowledgeDocumentDeletion} from '../../../entities/knowledge/api/knowledgeDocumentDeletionApi'
import {getApiErrorMessage} from '../../../shared/api/http'
import {getKnowledgeDeletionRequestKey, clearKnowledgeDeletionRequestKey}
    from '../lib/knowledgeDeletionRequestKey'

export type DocumentDeletionTarget = {document: KnowledgeDocument; key: string}
type CapabilityStatus = 'checking' | 'enabled' | 'disabled' | 'error'
type OperationTarget = {documentName: string; operation: KnowledgeDocumentDeletion}

const UNAVAILABLE_MESSAGES: Record<Exclude<CapabilityStatus, 'enabled'>, string> = {
    checking: 'Проверяем, доступно ли удаление документов…',
    disabled: 'Удаление документов сейчас недоступно. Документы и файлы остаются без изменений.',
    error: 'Не удалось проверить доступность удаления или получить историю операций. Новый запрос запрещён до проверки статуса на сервере.',
}

/**
 * Клиентская оркестрация удаления документов. Источник истины — серверный журнал.
 * При сетевой неопределённости исходный ключ сохраняется для повторной попытки.
 */
export function useKnowledgeDeletionController(options: {
    knowledgeBaseId: string
    isOwner: boolean
    setError: (message: string) => void
    refresh: () => Promise<unknown>
}) {
    const {knowledgeBaseId, isOwner, setError, refresh} = options
    const [capabilityStatus, setCapabilityStatus] = useState<CapabilityStatus>('checking')
    const [target, setTarget] = useState<DocumentDeletionTarget | null>(null)
    const [operationTarget, setOperationTarget] = useState<OperationTarget | null>(null)
    const [busy, setBusy] = useState(false)
    const inFlight = useRef(false)
    const pendingKeys = useRef(new Map<string, string>())
    const scope = useRef(knowledgeBaseId)
    const ownerRef = useRef(isOwner)
    scope.current = knowledgeBaseId
    ownerRef.current = isOwner
    const [operations, setOperations] = useState<KnowledgeDocumentDeletion[]>([])
    const [validatedBaseId, setValidatedBaseId] = useState('')
    const [historyError, setHistoryError] = useState(false)
    const available = isOwner && capabilityStatus === 'enabled'
        && validatedBaseId === knowledgeBaseId && !historyError

    useEffect(() => {
        // При смене tenant, базы или прав нельзя сохранить прежнюю историю и ключи.
        pendingKeys.current.clear()
        setTarget(null)
        setOperationTarget(null)
        if (!isOwner || !knowledgeBaseId) {
            setCapabilityStatus('disabled')
            setValidatedBaseId('')
            setOperations([])
            setHistoryError(false)
            return
        }
        const controller = new AbortController()
        setCapabilityStatus('checking')
        setValidatedBaseId('')
        setHistoryError(false)
        setOperations([])

        void Promise.allSettled([
            deletionCapability(knowledgeBaseId, controller.signal),
            listDocumentDeletions(knowledgeBaseId, controller.signal),
        ]).then(([capability, history]) => {
            if (controller.signal.aborted) return
            if (history.status === 'fulfilled') {
                setOperations(history.value)
                setHistoryError(false)
            } else {
                setOperations([])
                setHistoryError(true)
            }
            if (capability.status === 'rejected' || history.status === 'rejected') {
                setCapabilityStatus('error')
                setValidatedBaseId('')
            } else {
                setValidatedBaseId(capability.value ? knowledgeBaseId : '')
                setCapabilityStatus(capability.value ? 'enabled' : 'disabled')
            }
        })
        return () => controller.abort()
    }, [knowledgeBaseId, isOwner])

    useEffect(() => {
        if (!isOwner || !knowledgeBaseId) return
        const controller = new AbortController()
        const handle = window.setInterval(() => {
            // История читается даже при выключенном приёме удаления.
            void listDocumentDeletions(knowledgeBaseId, controller.signal)
                .then(active => {
                    if (controller.signal.aborted) return
                    setOperations(active)
                    setHistoryError(false)
                })
                .catch(() => {
                    if (controller.signal.aborted) return
                    setHistoryError(true)
                    setOperations([])
                    setCapabilityStatus('error')
                    setValidatedBaseId('')
                })
        }, 12_000)
        return () => { controller.abort(); window.clearInterval(handle) }
    }, [isOwner, knowledgeBaseId])

    function openOperation(document: KnowledgeDocument, operation: KnowledgeDocumentDeletion) {
        if (!isOwner || inFlight.current) return
        setTarget(null)
        setOperationTarget({documentName: document.name, operation})
    }

    function requestDeletion(document: KnowledgeDocument) {
        if (!isOwner || inFlight.current) return
        const existing = operations.find(op => op.documentId === document.id)
        if (existing) {
            openOperation(document, existing)
            return
        }
        if (!available) return
        const identity = `${knowledgeBaseId}:${document.id}:${document.version}`
        const key = pendingKeys.current.get(identity)
            ?? getKnowledgeDeletionRequestKey('document', knowledgeBaseId, document.id, document.version)
        pendingKeys.current.set(identity, key)
        setOperationTarget(null)
        setTarget({document, key})
    }

    function cancelDeletion() {
        if (!inFlight.current) setTarget(null)
        // Сохранение ключа безопаснее создания нового при потере HTTP-ответа.
    }

    function closeOperation() {
        if (!inFlight.current) setOperationTarget(null)
    }

    async function reconcile(document: KnowledgeDocument, baseId: string) {
        try {
            const current = await listDocumentDeletions(baseId)
            if (scope.current !== baseId) return false
            setOperations(current)
            setHistoryError(false)
            const existing = current.find(op => op.documentId === document.id)
            if (!existing) return false
            setOperationTarget({documentName: document.name, operation: existing})
            setTarget(null)
            pendingKeys.current.delete(`${baseId}:${document.id}:${document.version}`)
            clearKnowledgeDeletionRequestKey('document', baseId, document.id, document.version)
            return true
        } catch {
            return false
        }
    }

    async function confirmDeletion() {
        if (!target || !available || inFlight.current) return
        const {document, key} = target
        const baseId = knowledgeBaseId
        inFlight.current = true
        setBusy(true)
        setError('')
        try {
            // Предварительная проверка отделена от DELETE. Если сервер запретил
            // удаление или история недоступна, нет неопределённого результата
            // физического удаления и нет причин запускать reconcile.
            let stillAllowed: boolean
            let freshHistory: KnowledgeDocumentDeletion[]
            try {
                [stillAllowed, freshHistory] = await Promise.all([
                    deletionCapability(baseId), listDocumentDeletions(baseId),
                ])
            } catch (cause) {
                if (scope.current === baseId && ownerRef.current) {
                    setCapabilityStatus('error')
                    setValidatedBaseId('')
                    setHistoryError(true)
                    setError(getApiErrorMessage(cause,
                        'Не удалось перепроверить разрешение или историю. Удаление запрещено.'))
                }
                throw cause
            }
            if (scope.current !== baseId || !ownerRef.current) return
            setOperations(freshHistory)
            setHistoryError(false)
            if (!stillAllowed) {
                // Запрет сервера не является потерянным ответом DELETE.
                // Не отправляем мутацию и закрываем повторное подтверждение.
                setCapabilityStatus('disabled')
                setValidatedBaseId('')
                setError('Удаление отключено на сервере.')
                throw new Error('Удаление отключено на сервере.')
            }
            const existing = freshHistory.find(op => op.documentId === document.id)
            if (existing) {
                // Операция уже существует. Сохраняем серверное состояние
                // и не создаём вторую заявку на тот же документ.
                setOperationTarget({documentName: document.name, operation: existing})
                setTarget(null)
                pendingKeys.current.delete(`${baseId}:${document.id}:${document.version}`)
                clearKnowledgeDeletionRequestKey('document', baseId, document.id, document.version)
                return
            }

            let accepted: KnowledgeDocumentDeletion
            try {
                accepted = await acceptDocumentDeletion(baseId, document.id, document.version, key)
            } catch (cause) {
                if (scope.current !== baseId || !ownerRef.current) return
                // Только неопределённый результат реального DELETE требует
                // сверки журнала. При недоступной истории исходный ключ не меняем.
                const found = await reconcile(document, baseId)
                if (!found) {
                    setError(getApiErrorMessage(cause,
                        'Не удалось подтвердить удаление. Проверьте статус, прежде чем повторять запрос.'))
                    throw cause
                }
                return
            }
            if (scope.current !== baseId || !ownerRef.current) return
            if (accepted.documentId !== document.id) {
                // Чужой ID в ответе не допускает ни успешного статуса,
                // ни новой попытки до проверки серверных данных.
                setCapabilityStatus('error')
                setValidatedBaseId('')
                setHistoryError(true)
                setError('Сервер вернул операцию другого документа. Повторное удаление заблокировано.')
                throw new Error('Сервер вернул операцию другого документа.')
            }
            setOperations(current => [accepted, ...current.filter(o => o.operationId !== accepted.operationId)])
            setOperationTarget({documentName: document.name, operation: accepted})
            setTarget(null)
            pendingKeys.current.delete(`${baseId}:${document.id}:${document.version}`)
            clearKnowledgeDeletionRequestKey('document', baseId, document.id, document.version)
            try {
                await refresh()
            } catch {
                // Состояние операции уже подтверждено. Ошибка обновления
                // каталога не отменяет успешное принятие заявки на сервере.
                setError('Удаление принято сервером, но список документов не обновился. Обновите страницу.')
            }
        } finally {
            // Любая ветка, включая отказ и ошибку сети, освобождает UI-lock.
            inFlight.current = false
            setBusy(false)
        }
    }

    const visibleOperation = operationTarget
        ? operations.find(op => op.operationId === operationTarget.operation.operationId)
            ?? operationTarget.operation
        : null
    const unavailableReason = available ? '' : capabilityStatus === 'enabled'
        ? UNAVAILABLE_MESSAGES.checking : UNAVAILABLE_MESSAGES[capabilityStatus]

    return {
        available,
        canShowAction: isOwner,
        unavailableReason,
        target, busy, operations, historyError,
        operationTarget, visibleOperation,
        requestDeletion, cancelDeletion, confirmDeletion, openOperation, closeOperation,
    }
}
