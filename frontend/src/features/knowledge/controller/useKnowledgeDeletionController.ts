// frontend/src/features/knowledge/controller/useKnowledgeDeletionController.ts
import {useEffect, useLayoutEffect, useRef, useState} from 'react'
import type {KnowledgeDocument} from '../../../entities/knowledge/api/knowledgeDocumentApi'
import {
    acceptDocumentDeletion,
    deletionCapability,
    listDocumentDeletions,
} from '../../../entities/knowledge/api/knowledgeDocumentDeletionApi'
import type {KnowledgeDocumentDeletion} from '../../../entities/knowledge/api/knowledgeDocumentDeletionApi'
import {getApiErrorMessage} from '../../../shared/api/http'
import {
    clearKnowledgeDeletionRequestKey,
    getKnowledgeDeletionRequestKey,
} from '../lib/knowledgeDeletionRequestKey'

const HISTORY_POLL_INTERVAL_MS = 12_000

export type DocumentDeletionTarget = {
    document: KnowledgeDocument
    key: string
}

type CapabilityStatus = 'checking' | 'enabled' | 'disabled' | 'error'

type OperationTarget = {
    documentName: string
    operation: KnowledgeDocumentDeletion
}

const UNAVAILABLE_MESSAGES: Record<Exclude<CapabilityStatus, 'enabled'>, string> = {
    checking: 'Проверяем, доступно ли удаление документов…',
    disabled: 'Удаление документов сейчас недоступно. Документы и файлы остаются без изменений.',
    error: 'Не удалось проверить доступность удаления или получить историю операций. Новый запрос запрещён до проверки статуса на сервере.',
}

/**
 * Клиентская оркестрация удаления документов.
 *
 * Источник истины — серверный журнал операций. Клиент не считает HTTP 202
 * завершением физического удаления и не создаёт новый intent, если операция
 * уже существует или её результат после сетевой ошибки неизвестен.
 *
 * Защиты:
 * - scope и права проверяются после каждого асинхронного шага;
 * - перед DELETE повторно проверяются capability и история;
 * - для неопределённого результата сохраняются исходные version и idempotency key;
 * - неизвестное состояние сервера блокирует новые destructive-запросы;
 * - polling читает только историю и не зависит от feature flag DELETE.
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
    const [operations, setOperations] = useState<KnowledgeDocumentDeletion[]>([])
    const [validatedBaseId, setValidatedBaseId] = useState('')
    const [historyError, setHistoryError] = useState(false)

    const inFlight = useRef(false)
    const pendingKeys = useRef(new Map<string, string>())
    const scope = useRef(knowledgeBaseId)
    const ownerRef = useRef(isOwner)

    // Синхронизируем защитный контекст после commit, до пассивных эффектов.
    // Это закрывает окно, в котором поздний ответ старой базы мог бы обновить UI.
    useLayoutEffect(() => {
        scope.current = knowledgeBaseId
        ownerRef.current = isOwner
    }, [knowledgeBaseId, isOwner])

    const available = isOwner
        && capabilityStatus === 'enabled'
        && validatedBaseId === knowledgeBaseId
        && !historyError

    useEffect(() => {
        // При смене базы или прав нельзя сохранять target, историю и локальные ключи
        // предыдущего контекста. Сам persistent idempotency key не удаляем:
        // он может понадобиться при повторном открытии после сетевой неопределённости.
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
            if (
                controller.signal.aborted
                || scope.current !== knowledgeBaseId
                || !ownerRef.current
            ) {
                return
            }

            // История и capability независимы: даже если DELETE выключен,
            // read-only история остаётся доступной для расследования.
            if (history.status === 'fulfilled') {
                setOperations(history.value)
                setHistoryError(false)
            } else {
                setOperations([])
                setHistoryError(true)
            }

            // Ошибка любой обязательной проверки означает fail closed.
            if (capability.status === 'rejected' || history.status === 'rejected') {
                setCapabilityStatus('error')
                setValidatedBaseId('')
                return
            }

            setValidatedBaseId(capability.value ? knowledgeBaseId : '')
            setCapabilityStatus(capability.value ? 'enabled' : 'disabled')
        })

        return () => controller.abort()
    }, [knowledgeBaseId, isOwner])

    useEffect(() => {
        if (!isOwner || !knowledgeBaseId) return

        const controller = new AbortController()

        const handle = window.setInterval(() => {
            if (
                controller.signal.aborted
                || scope.current !== knowledgeBaseId
                || !ownerRef.current
            ) {
                return
            }

            // Историю читаем даже при выключенном DELETE capability.
            void listDocumentDeletions(knowledgeBaseId, controller.signal)
                .then(freshHistory => {
                    if (
                        controller.signal.aborted
                        || scope.current !== knowledgeBaseId
                        || !ownerRef.current
                    ) {
                        return
                    }

                    setOperations(freshHistory)
                    setHistoryError(false)
                })
                .catch(() => {
                    if (
                        controller.signal.aborted
                        || scope.current !== knowledgeBaseId
                        || !ownerRef.current
                    ) {
                        return
                    }

                    // Неизвестное состояние журнала блокирует новые удаления.
                    // Не восстанавливаем capability автоматически после ошибки:
                    // требуется новая полная проверка при повторном входе в scope.
                    setOperations([])
                    setHistoryError(true)
                    setCapabilityStatus('error')
                    setValidatedBaseId('')
                })
        }, HISTORY_POLL_INTERVAL_MS)

        return () => {
            controller.abort()
            window.clearInterval(handle)
        }
    }, [isOwner, knowledgeBaseId])

    function openOperation(
        document: KnowledgeDocument,
        operation: KnowledgeDocumentDeletion,
    ) {
        if (!isOwner || inFlight.current) return

        if (
            document.knowledgeBaseId !== knowledgeBaseId
            || operation.documentId !== document.id
        ) {
            setError('Документ или операция не принадлежат выбранной базе. Обновите список.')
            return
        }

        setTarget(null)
        setOperationTarget({
            documentName: document.name,
            operation,
        })
    }

    function requestDeletion(document: KnowledgeDocument) {
        if (!isOwner || inFlight.current) return

        // Fail closed: документ из предыдущего scope не отправляется
        // на DELETE с идентификатором текущей базы.
        if (document.knowledgeBaseId !== knowledgeBaseId) {
            setError('Документ не принадлежит выбранной базе. Обновите список документов.')
            return
        }

        const existing = operations.find(operation => operation.documentId === document.id)
        if (existing) {
            openOperation(document, existing)
            return
        }

        if (!available) return

        const identity = `${knowledgeBaseId}:${document.id}:${document.version}`
        const key = pendingKeys.current.get(identity)
            ?? getKnowledgeDeletionRequestKey(
                'document',
                knowledgeBaseId,
                document.id,
                document.version,
            )

        pendingKeys.current.set(identity, key)
        setOperationTarget(null)
        setTarget({document, key})
    }

    function cancelDeletion() {
        if (!inFlight.current) {
            setTarget(null)
        }

        // Ключ намеренно не очищаем: пользователь мог потерять HTTP-ответ
        // после того, как сервер уже принял запрос.
    }

    function closeOperation() {
        if (!inFlight.current) {
            setOperationTarget(null)
        }
    }

    async function reconcile(
        document: KnowledgeDocument,
        baseId: string,
    ): Promise<boolean> {
        try {
            const currentHistory = await listDocumentDeletions(baseId)

            if (scope.current !== baseId || !ownerRef.current) {
                return false
            }

            const existing = currentHistory.find(
                operation => operation.documentId === document.id,
            )

            setOperations(currentHistory)
            setHistoryError(false)

            if (!existing) {
                return false
            }

            setOperationTarget({
                documentName: document.name,
                operation: existing,
            })
            setTarget(null)

            pendingKeys.current.delete(
                `${baseId}:${document.id}:${document.version}`,
            )
            clearKnowledgeDeletionRequestKey(
                'document',
                baseId,
                document.id,
                document.version,
            )

            return true
        } catch {
            // Неизвестное состояние не считается отсутствием операции.
            // Исходный idempotency key остаётся для безопасной сверки/повтора.
            return false
        }
    }

    async function confirmDeletion() {
        if (!target || !available || inFlight.current) return

        const {document, key} = target
        const baseId = knowledgeBaseId

        if (document.knowledgeBaseId !== baseId) {
            setError('Документ больше не принадлежит выбранной базе. Обновите список документов.')
            setTarget(null)
            return
        }

        inFlight.current = true
        setBusy(true)
        setError('')

        try {
            // Предварительная проверка отделена от DELETE. Ошибка проверки
            // не трактуется как потерянный ответ мутации и не запускает reconcile.
            let stillAllowed: boolean
            let freshHistory: KnowledgeDocumentDeletion[]

            try {
                [stillAllowed, freshHistory] = await Promise.all([
                    deletionCapability(baseId),
                    listDocumentDeletions(baseId),
                ])
            } catch (cause) {
                if (scope.current === baseId && ownerRef.current) {
                    setCapabilityStatus('error')
                    setValidatedBaseId('')
                    setHistoryError(true)
                    setError(getApiErrorMessage(
                        cause,
                        'Не удалось перепроверить разрешение или историю. Удаление запрещено.',
                    ))
                }
                throw cause
            }

            if (scope.current !== baseId || !ownerRef.current) return

            setOperations(freshHistory)
            setHistoryError(false)

            if (!stillAllowed) {
                setCapabilityStatus('disabled')
                setValidatedBaseId('')
                setError('Удаление отключено на сервере.')
                throw new Error('Удаление отключено на сервере.')
            }

            const existing = freshHistory.find(
                operation => operation.documentId === document.id,
            )

            if (existing) {
                // Операция могла появиться после открытия диалога.
                // Второй DELETE не отправляем.
                setOperationTarget({
                    documentName: document.name,
                    operation: existing,
                })
                setTarget(null)

                pendingKeys.current.delete(
                    `${baseId}:${document.id}:${document.version}`,
                )
                clearKnowledgeDeletionRequestKey(
                    'document',
                    baseId,
                    document.id,
                    document.version,
                )
                return
            }

            let accepted: KnowledgeDocumentDeletion

            try {
                accepted = await acceptDocumentDeletion(
                    baseId,
                    document.id,
                    document.version,
                    key,
                )
            } catch (cause) {
                if (scope.current !== baseId || !ownerRef.current) return

                // Только неопределённый результат реального DELETE требует сверки.
                const found = await reconcile(document, baseId)

                if (scope.current !== baseId || !ownerRef.current) return

                if (!found) {
                    setError(getApiErrorMessage(
                        cause,
                        'Не удалось подтвердить удаление. Проверьте статус, прежде чем повторять запрос.',
                    ))
                    throw cause
                }

                return
            }

            if (scope.current !== baseId || !ownerRef.current) return

            if (accepted.documentId !== document.id) {
                // Ответ другого документа — нарушение контракта API.
                // Не очищаем ключ и блокируем новые destructive-запросы.
                setCapabilityStatus('error')
                setValidatedBaseId('')
                setHistoryError(true)
                setError('Сервер вернул операцию другого документа. Повторное удаление заблокировано.')
                throw new Error('Сервер вернул операцию другого документа.')
            }

            const operationIdCollision = freshHistory.some(
                operation => operation.operationId === accepted.operationId
                    && operation.documentId !== accepted.documentId,
            )

            if (operationIdCollision) {
                setCapabilityStatus('error')
                setValidatedBaseId('')
                setHistoryError(true)
                setError('Сервер повторно использовал идентификатор другой операции. Удаление заблокировано.')
                throw new Error('Сервер вернул повторный идентификатор операции.')
            }

            setOperations(current => [
                accepted,
                ...current.filter(
                    operation => operation.operationId !== accepted.operationId,
                ),
            ])
            setOperationTarget({
                documentName: document.name,
                operation: accepted,
            })
            setTarget(null)

            pendingKeys.current.delete(
                `${baseId}:${document.id}:${document.version}`,
            )
            clearKnowledgeDeletionRequestKey(
                'document',
                baseId,
                document.id,
                document.version,
            )

            try {
                await refresh()
            } catch {
                // Принятие операции сервером уже подтверждено.
                // Ошибка обновления каталога не отменяет принятую заявку.
                if (scope.current === baseId && ownerRef.current) {
                    setError('Удаление принято сервером, но список документов не обновился. Обновите страницу.')
                }
            }
        } finally {
            inFlight.current = false
            setBusy(false)
        }
    }

    const visibleOperation = operationTarget
        ? operations.find(
            operation => operation.operationId === operationTarget.operation.operationId,
        ) ?? operationTarget.operation
        : null

    const unavailableReason = available
        ? ''
        : historyError || capabilityStatus === 'error'
            ? UNAVAILABLE_MESSAGES.error
            : capabilityStatus === 'enabled'
                ? UNAVAILABLE_MESSAGES.checking
                : UNAVAILABLE_MESSAGES[capabilityStatus]

    return {
        available,
        canShowAction: isOwner,
        unavailableReason,
        target,
        busy,
        operations,
        historyError,
        operationTarget,
        visibleOperation,
        requestDeletion,
        cancelDeletion,
        confirmDeletion,
        openOperation,
        closeOperation,
    }
}
