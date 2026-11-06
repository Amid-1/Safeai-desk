// frontend/src/features/knowledge/ui/KnowledgeBaseDeleteAction.tsx
import {useCallback, useEffect, useLayoutEffect, useRef, useState} from 'react'
import type {KnowledgeBase} from '../../../entities/knowledge/api/knowledgeApi'
import {
    acceptBaseDeletion,
    baseDeletionCapability,
    getBaseDeletion,
    listBaseDeletions,
} from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'
import type {BaseDeletionOperation} from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'
import {ApiError, getApiErrorMessage} from '../../../shared/api/http'
import {
    clearKnowledgeDeletionRequestKey,
    getKnowledgeDeletionRequestKey,
} from '../lib/knowledgeDeletionRequestKey'
import {KnowledgeErasureDialog} from './KnowledgeErasureDialog'

const OPERATION_POLL_INTERVAL_MS = 12_000

type Check = 'checking' | 'enabled' | 'disabled' | 'error'

type Inspection =
    | {kind: 'stale'}
    | {kind: 'active'; operation: BaseDeletionOperation}
    | {kind: 'allowed'}
    | {kind: 'disabled'; reason: string}
    | {kind: 'error'; reason: string}

/**
 * Управление удалением базы знаний.
 *
 * Сервер остаётся единственным источником истины. Перед каждым DELETE
 * повторно проверяются capability, история и текущая операция базы.
 *
 * HTTP 202 означает принятие операции, а не завершение физического удаления.
 * После неопределённого сетевого результата используется тот же version
 * и тот же Idempotency-Key. Повторный intent не создаётся, пока состояние
 * предыдущей операции не сверено с сервером.
 */
export function KnowledgeBaseDeleteAction({base, onAccepted}: {
    base: KnowledgeBase
    onAccepted: () => void
}) {
    const [open, setOpen] = useState(false)
    const [check, setCheck] = useState<Check>('checking')
    const [checkedBaseId, setCheckedBaseId] = useState('')
    const [checkedVersion, setCheckedVersion] = useState<number | null>(null)
    const [name, setName] = useState('')
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')
    const [operation, setOperation] = useState<BaseDeletionOperation | null>(null)
    const [statusError, setStatusError] = useState('')
    const [acceptedDuringDialog, setAcceptedDuringDialog] = useState(false)

    const requestVersion = useRef<number | null>(null)
    const requestKey = useRef<string | null>(null)
    const inFlight = useRef(false)
    const scope = useRef(base.id)
    const baseVersionRef = useRef(base.version)
    const lifecycleStateRef = useRef(base.lifecycleState)

    // Обновляем защитный контекст после commit и до пассивных эффектов.
    // Поздний ответ старого запроса не должен менять состояние другой базы
    // или продолжать подтверждение после смены версии/lifecycle.
    useLayoutEffect(() => {
        scope.current = base.id
        baseVersionRef.current = base.version
        lifecycleStateRef.current = base.lifecycleState
    }, [base.id, base.version, base.lifecycleState])

    // Смена базы сбрасывает UI-контекст, но не влияет на уже отправленный
    // HTTP-запрос. Его ответ будет проигнорирован проверкой scope.
    useEffect(() => {
        requestKey.current = null
        requestVersion.current = null
        setOperation(null)
        setAcceptedDuringDialog(false)
        setStatusError('')
        setError('')
        setCheckedBaseId('')
        setCheckedVersion(null)
        setCheck('checking')
        setName('')
    }, [base.id])

    /**
     * Единая серверная проверка для открытия окна и непосредственно перед DELETE.
     * 404 от getBaseDeletion означает отсутствие текущей операции; остальные
     * ошибки считаются неизвестным состоянием и блокируют destructive-действие.
     */
    const inspectServerState = useCallback(async (
        baseId: string,
        isDeleting: boolean,
        signal?: AbortSignal,
    ): Promise<Inspection> => {
        const [capability, history, current] = await Promise.allSettled([
            baseDeletionCapability(baseId, signal),
            listBaseDeletions(signal),
            getBaseDeletion(baseId, signal),
        ])

        if (signal?.aborted || scope.current !== baseId) {
            return {kind: 'stale'}
        }

        if (history.status === 'rejected') {
            return {
                kind: 'error',
                reason: 'Не удалось получить историю операций. Новый запрос запрещён до проверки её состояния.',
            }
        }

        if (
            current.status === 'rejected'
            && !(current.reason instanceof ApiError && current.reason.status === 404)
        ) {
            return {
                kind: 'error',
                reason: 'Не удалось сверить текущую операцию базы. Новый запрос запрещён до проверки сервера.',
            }
        }

        if (
            current.status === 'fulfilled'
            && current.value.knowledgeBaseId !== baseId
        ) {
            return {
                kind: 'error',
                reason: 'Сервер вернул операцию другой базы. Удаление заблокировано до проверки администратором.',
            }
        }

        const ownHistory = history.value.filter(
            item => item.knowledgeBaseId === baseId,
        )

        // Текущая операция точнее ограниченной истории последних записей.
        // Даже DELETED не разрешает создавать новый intent для той же базы.
        const existing = current.status === 'fulfilled'
            ? current.value
            : ownHistory.find(item => item.state !== 'DELETED') ?? ownHistory[0]

        if (existing) {
            return {
                kind: 'active',
                operation: existing,
            }
        }

        if (isDeleting) {
            return {
                kind: 'error',
                reason: 'База находится в удалении, но её операция не найдена в истории. Повторное удаление запрещено.',
            }
        }

        if (capability.status === 'rejected') {
            return {
                kind: 'error',
                reason: getApiErrorMessage(
                    capability.reason,
                    'Не удалось проверить разрешение сервера на удаление.',
                ),
            }
        }

        return capability.value
            ? {kind: 'allowed'}
            : {
                kind: 'disabled',
                reason: 'Удаление отключено на сервере.',
            }
    }, [])

    /**
     * Применяет результат проверки к UI.
     * Если в браузере остался ключ от неопределённого запроса и сервер нашёл
     * операцию, считаем intent сверенным и очищаем его локальный ключ.
     */
    const applyInspection = useCallback((
        result: Inspection,
        baseId: string,
        recoverPendingIntent = false,
    ) => {
        if (result.kind === 'stale') return

        if (result.kind === 'active') {
            setOperation(result.operation)
            setCheck('disabled')
            setCheckedBaseId(baseId)
            setCheckedVersion(base.version)
            setError('')
            setStatusError('')

            if (recoverPendingIntent && requestKey.current) {
                const originalVersion = requestVersion.current ?? base.version

                requestKey.current = null
                requestVersion.current = null
                clearKnowledgeDeletionRequestKey(
                    'base',
                    baseId,
                    baseId,
                    originalVersion,
                )
                setAcceptedDuringDialog(true)
            }

            return
        }

        setOperation(null)
        setStatusError('')

        if (result.kind === 'allowed') {
            setCheck('enabled')
            setCheckedBaseId(baseId)
            setCheckedVersion(base.version)
            setError('')
            return
        }

        setCheckedBaseId('')
        setCheckedVersion(null)
        setCheck(result.kind === 'disabled' ? 'disabled' : 'error')
        setError(result.reason)
    }, [base.version])

    useEffect(() => {
        if (!open) return

        const controller = new AbortController()
        const baseId = base.id

        setCheck('checking')
        setCheckedBaseId('')
        setCheckedVersion(null)
        setOperation(null)
        setError('')
        setStatusError('')

        void inspectServerState(
            baseId,
            base.lifecycleState === 'DELETING',
            controller.signal,
        ).then(result => {
            if (
                controller.signal.aborted
                || scope.current !== baseId
                || result.kind === 'stale'
            ) {
                return
            }

            applyInspection(result, baseId, true)
        })

        return () => controller.abort()
    }, [
        open,
        base.id,
        base.version,
        base.lifecycleState,
        inspectServerState,
        applyInspection,
    ])

    // Используем примитивные поля вместо объекта operation:
    // polling не должен перезапускаться только из-за новой ссылки на объект.
    const operationId = operation?.operationId
    const operationState = operation?.state
    const operationBaseId = operation?.knowledgeBaseId

    useEffect(() => {
        if (
            !open
            || !operationId
            || operationBaseId !== base.id
            || operationState === 'DELETED'
        ) {
            return
        }

        const controller = new AbortController()
        const baseId = base.id

        const poll = () => {
            if (controller.signal.aborted || scope.current !== baseId) return

            void getBaseDeletion(baseId, controller.signal)
                .then(fresh => {
                    if (controller.signal.aborted || scope.current !== baseId) return

                    if (
                        fresh.knowledgeBaseId !== baseId
                        || fresh.operationId !== operationId
                    ) {
                        setStatusError(
                            'Сервер вернул другую операцию удаления. Обратитесь к администратору.',
                        )
                        return
                    }

                    setOperation(fresh)
                    setStatusError('')
                })
                .catch(cause => {
                    if (
                        !controller.signal.aborted
                        && scope.current === baseId
                    ) {
                        setStatusError(getApiErrorMessage(
                            cause,
                            'Не удалось обновить статус удаления.',
                        ))
                    }
                })
        }

        const timer = window.setInterval(
            poll,
            OPERATION_POLL_INTERVAL_MS,
        )

        return () => {
            controller.abort()
            window.clearInterval(timer)
        }
    }, [
        open,
        operationId,
        operationState,
        operationBaseId,
        base.id,
    ])

    function close() {
        if (inFlight.current) return

        setOpen(false)
        setName('')
        setOperation(null)
        setStatusError('')
        setCheckedBaseId('')
        setCheckedVersion(null)
        setCheck('checking')

        // Неопределённый запрос сохраняет исходные version и idempotency key.
        if (!requestKey.current) {
            requestVersion.current = null
        }

        if (acceptedDuringDialog) {
            setAcceptedDuringDialog(false)
            onAccepted()
        }
    }

    async function reconcile(baseId: string): Promise<boolean> {
        const [history, current] = await Promise.allSettled([
            listBaseDeletions(),
            getBaseDeletion(baseId),
        ])

        if (scope.current !== baseId) return false

        if (
            current.status === 'fulfilled'
            && current.value.knowledgeBaseId !== baseId
        ) {
            setCheck('error')
            setCheckedBaseId('')
            setCheckedVersion(null)
            setError(
                'Сервер вернул операцию другой базы. Повторный DELETE заблокирован.',
            )
            return false
        }

        if (
            history.status === 'rejected'
            || (
                current.status === 'rejected'
                && !(current.reason instanceof ApiError && current.reason.status === 404)
            )
        ) {
            // Неизвестное состояние сервера нельзя трактовать как отсутствие операции.
            setCheck('error')
            setCheckedBaseId('')
            setCheckedVersion(null)
            setError(
                'Не удалось достоверно сверить удаление. Повтор запрещён до восстановления истории.',
            )
            return false
        }

        const ownHistory = history.value.filter(
            item => item.knowledgeBaseId === baseId,
        )

        const existing = current.status === 'fulfilled'
            ? current.value
            : ownHistory.find(item => item.state !== 'DELETED') ?? ownHistory[0]

        if (!existing) return false

        setOperation(existing)
        setAcceptedDuringDialog(true)
        setStatusError('')

        const originalVersion = requestVersion.current ?? base.version
        requestKey.current = null
        requestVersion.current = null
        clearKnowledgeDeletionRequestKey(
            'base',
            baseId,
            baseId,
            originalVersion,
        )

        return true
    }

    async function confirm() {
        if (
            inFlight.current
            || !open
            || check !== 'enabled'
            || checkedBaseId !== base.id
            || checkedVersion !== base.version
            || operation
        ) {
            return
        }

        inFlight.current = true
        setBusy(true)
        setError('')

        const baseId = base.id
        const baseVersionAtStart = base.version
        const lifecycleAtStart = base.lifecycleState
        const hadPendingIntent = requestKey.current !== null

        try {
            // Проверяем capability, history и current непосредственно перед DELETE.
            // Операция могла появиться после открытия диалога, а feature flag
            // мог быть выключен администратором в другой сессии.
            const inspection = await inspectServerState(
                baseId,
                base.lifecycleState === 'DELETING',
            )

            if (scope.current !== baseId || inspection.kind === 'stale') {
                return
            }

            // Если карточка изменилась во время preflight, не создаём intent
            // на основании устаревшего снимка. При повторе уже существующего
            // intent сохраняем его первоначальную версию и ключ.
            if (
                lifecycleStateRef.current !== lifecycleAtStart
                || (!hadPendingIntent && baseVersionRef.current !== baseVersionAtStart)
            ) {
                // Изменение версии/lifecycle уже запускает новый inspection effect.
                // Не перезаписываем его результат устаревшим состоянием checking.
                return
            }

            if (inspection.kind !== 'allowed') {
                applyInspection(inspection, baseId, true)
                return
            }

            // На повторной попытке используем исходные version и key,
            // даже если карточка базы уже обновилась.
            if (!requestKey.current) {
                requestKey.current = getKnowledgeDeletionRequestKey(
                    'base',
                    baseId,
                    baseId,
                    baseVersionAtStart,
                )
                requestVersion.current = baseVersionAtStart
            }

            let accepted: BaseDeletionOperation

            try {
                accepted = await acceptBaseDeletion(
                    baseId,
                    requestVersion.current ?? base.version,
                    requestKey.current,
                )
            } catch (cause) {
                if (scope.current !== baseId) return

                const found = await reconcile(baseId)

                // Пока шла сверка, пользователь мог переключить базу.
                if (scope.current !== baseId) return

                if (!found) {
                    setError(getApiErrorMessage(
                        cause,
                        'Не удалось подтвердить удаление. Проверьте историю перед повтором.',
                    ))
                }

                return
            }

            if (scope.current !== baseId) return

            if (accepted.knowledgeBaseId !== baseId) {
                // Несогласованный ответ не является подтверждением удаления.
                // Не очищаем ключ и блокируем новую попытку до проверки.
                setCheck('error')
                setCheckedBaseId('')
                setCheckedVersion(null)
                setError(
                    'Сервер вернул идентификатор другой базы. Новое удаление заблокировано.',
                )
                return
            }

            setOperation(accepted)
            setAcceptedDuringDialog(true)

            const originalVersion = requestVersion.current ?? base.version
            requestKey.current = null
            requestVersion.current = null
            clearKnowledgeDeletionRequestKey(
                'base',
                baseId,
                baseId,
                originalVersion,
            )
        } finally {
            inFlight.current = false
            setBusy(false)
        }
    }

    // Старую операцию нельзя даже кратковременно показывать после смены базы.
    const currentOperation = operation?.knowledgeBaseId === base.id
        ? operation
        : null

    const modalState = currentOperation
        ? 'operation'
        : check === 'checking'
            ? 'checking'
            : check === 'enabled'
                && checkedBaseId === base.id
                && checkedVersion === base.version
                ? 'confirm'
                : 'unavailable'

    return <>
        <button
            type="button"
            className="secondary-button"
            onClick={() => setOpen(true)}
            title={base.lifecycleState === 'DELETING'
                ? 'Посмотреть состояние ранее принятого удаления'
                : 'Открыть управление удалением базы'}
        >
            {base.lifecycleState === 'DELETING'
                ? 'Статус удаления…'
                : 'Удалить базу…'}
        </button>

        {open && <>
            {modalState === 'checking' && (
                <KnowledgeErasureDialog
                    mode="checking"
                    kind="base"
                    entityName={base.name}
                    onClose={close}
                />
            )}

            {modalState === 'unavailable' && (
                <KnowledgeErasureDialog
                    mode="unavailable"
                    kind="base"
                    entityName={base.name}
                    onClose={close}
                    checkFailed={check === 'error'}
                    reason={error || 'Удаление баз знаний сейчас недоступно.'}
                />
            )}

            {modalState === 'confirm' && (
                <KnowledgeErasureDialog
                    mode="confirm"
                    kind="base"
                    entityName={base.name}
                    busy={busy}
                    onClose={close}
                    typedName={name}
                    onTypedNameChange={setName}
                    error={error}
                    onConfirm={() => {
                        void confirm()
                    }}
                />
            )}

            {modalState === 'operation' && currentOperation && (
                <KnowledgeErasureDialog
                    mode="operation"
                    kind="base"
                    entityName={base.name}
                    onClose={close}
                    operation={currentOperation}
                    statusError={statusError}
                    historyHref="/knowledge#knowledge-base-deletion-history"
                />
            )}
        </>}
    </>
}
