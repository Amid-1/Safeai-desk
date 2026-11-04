// frontend/src/features/knowledge/ui/KnowledgeBaseDeleteAction.tsx
import {useEffect, useRef, useState} from 'react'
import type {KnowledgeBase} from '../../../entities/knowledge/api/knowledgeApi'
import {acceptBaseDeletion, baseDeletionCapability, getBaseDeletion, listBaseDeletions} from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'
import type {BaseDeletionOperation} from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'
import {ApiError, getApiErrorMessage} from '../../../shared/api/http'
import {getKnowledgeDeletionRequestKey, clearKnowledgeDeletionRequestKey}
    from '../lib/knowledgeDeletionRequestKey'
import {KnowledgeErasureDialog} from './KnowledgeErasureDialog'

type Check = 'checking' | 'enabled' | 'disabled' | 'error'

/**
 * Принятие удаления базы знаний: подтверждение, повтор с тем же ключом,
 * сверка существующих операций и наблюдение до ответа backend.
 * Повторный DELETE никогда не создаётся для базы с незавершённой операцией.
 */
export function KnowledgeBaseDeleteAction({base, onAccepted}: {
    base: KnowledgeBase
    onAccepted: () => void
}) {
    const [open, setOpen] = useState(false)
    const [check, setCheck] = useState<Check>('checking')
    const [checkedBaseId, setCheckedBaseId] = useState('')
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
    scope.current = base.id

    // При смене базы прежний запрос не может применяться к другому объекту.
    useEffect(() => {
        requestKey.current = null
        requestVersion.current = null
        setOperation(null)
        setAcceptedDuringDialog(false)
    }, [base.id])

    useEffect(() => {
        if (!open) return
        const controller = new AbortController()
        const baseId = base.id
        setCheck('checking')
        setCheckedBaseId('')
        setError('')
        setStatusError('')
        // История проверяется даже когда feature flag выключен.
        // Список ограничен 50 записями; current нужен для старых операций
        // данной базы, которые могли выпасть из общего списка.
        void Promise.allSettled([
            baseDeletionCapability(baseId, controller.signal),
            listBaseDeletions(controller.signal),
            getBaseDeletion(baseId, controller.signal),
        ]).then(([capability, history, current]) => {
            if (controller.signal.aborted || scope.current !== baseId) return
            if (history.status === 'rejected') {
                setCheck('error')
                setError('Не удалось получить историю операций. Новый запрос запрещён до проверки её состояния.')
                return
            }
            if (current.status === 'rejected'
                && !(current.reason instanceof ApiError && current.reason.status === 404)) {
                setCheck('error')
                setError('Не удалось сверить текущую операцию базы. Новый запрос запрещён до проверки сервера.')
                return
            }
            // Нельзя использовать состояние операции, которую сервер связал с другой базой.
            // Это нарушение контракта API, при котором безопаснее запретить DELETE.
            if (current.status === 'fulfilled' && current.value.knowledgeBaseId !== baseId) {
                setCheck('error')
                setError('Сервер вернул операцию другой базы. Удаление заблокировано до проверки администратором.')
                return
            }
            const own = history.value.filter(item => item.knowledgeBaseId === baseId)
            // Даже DELETED не превращается в разрешение создать новый intent.
            const active = current.status === 'fulfilled'
                ? current.value : own.find(item => item.state !== 'DELETED') ?? own[0]
            if (active) {
                setOperation(active)
                setCheckedBaseId(baseId)
                setCheck('disabled')
                return
            }
            if (base.lifecycleState === 'DELETING') {
                setCheck('error')
                setError('База находится в удалении, но её операция не найдена в истории. Повторное удаление запрещено.')
                return
            }
            if (capability.status === 'rejected') {
                setCheck('error')
                setError(getApiErrorMessage(capability.reason,
                    'Не удалось проверить разрешение сервера на удаление.'))
                return
            }
            setCheckedBaseId(baseId)
            setCheck(capability.value ? 'enabled' : 'disabled')
        })
        return () => controller.abort()
    }, [open, base.id, base.lifecycleState])

    useEffect(() => {
        if (!open || !operation || operation.state === 'DELETED') return
        const controller = new AbortController()
        const baseId = base.id
        const poll = () => {
            void getBaseDeletion(baseId, controller.signal)
                .then(fresh => {
                    if (controller.signal.aborted || scope.current !== baseId) return
                    if (fresh.knowledgeBaseId !== baseId || fresh.operationId !== operation.operationId) {
                        setStatusError('Сервер вернул другую операцию удаления. Обратитесь к администратору.')
                        return
                    }
                    setOperation(fresh)
                    setStatusError('')
                })
                .catch(cause => {
                    if (!controller.signal.aborted) setStatusError(getApiErrorMessage(cause,
                        'Не удалось обновить статус удаления.'))
                })
        }
        const timer = window.setInterval(poll, 12_000)
        return () => { controller.abort(); window.clearInterval(timer) }
    }, [open, operation?.operationId, operation?.state, base.id])

    function close() {
        if (inFlight.current) return
        setOpen(false)
        setName('')
        // При неопределённой сетевой ошибке ключ сохраняется даже после закрытия окна.
        if (!requestKey.current) requestVersion.current = null
        if (acceptedDuringDialog) {
            setAcceptedDuringDialog(false)
            onAccepted()
        }
    }

    async function reconcile(baseId: string): Promise<boolean> {
        // При потере ответа DELETE сначала сверяем обе серверные точки чтения.
        // Текущая операция может отсутствовать в ограниченной последней истории.
        const [history, current] = await Promise.allSettled([
            listBaseDeletions(), getBaseDeletion(baseId),
        ])
        if (scope.current !== baseId) return false
        if (current.status === 'fulfilled' && current.value.knowledgeBaseId !== baseId) {
            setCheck('error')
            setCheckedBaseId('')
            setError('Сервер вернул операцию другой базы. Повторный DELETE заблокирован.')
            return false
        }
        if (history.status === 'rejected'
            || (current.status === 'rejected'
                && !(current.reason instanceof ApiError && current.reason.status === 404))) {
            // Неизвестное состояние сервера нельзя трактовать как отсутствие операции.
            setCheck('error')
            setCheckedBaseId('')
            setError('Не удалось достоверно сверить удаление. Повтор запрещён до восстановления истории.')
            return false
        }
        const own = history.value.filter(item => item.knowledgeBaseId === baseId)
        const existing = current.status === 'fulfilled'
            ? current.value : own.find(item => item.state !== 'DELETED') ?? own[0]
        if (!existing) return false
        setOperation(existing)
        setAcceptedDuringDialog(true)
        setStatusError('')
        requestKey.current = null
        clearKnowledgeDeletionRequestKey('base', baseId, baseId, requestVersion.current ?? base.version)
        requestVersion.current = null
        return true
    }

    async function confirm() {
        if (inFlight.current || !open || check !== 'enabled'
            || checkedBaseId !== base.id || operation) return
        inFlight.current = true
        setBusy(true)
        setError('')
        const baseId = base.id
        try {
            if (!requestKey.current) {
                requestKey.current = getKnowledgeDeletionRequestKey('base', baseId, baseId, base.version)
                requestVersion.current = base.version
            }
            // На повторе используем ту же версию и тот же ключ, даже если карточка обновилась.
            const accepted = await acceptBaseDeletion(baseId, requestVersion.current ?? base.version, requestKey.current)
            if (scope.current !== baseId) return
            if (accepted.knowledgeBaseId !== baseId) {
                // Несогласованный ответ не является сетевой ошибкой: нельзя
                // повторять запрос, пока администратор не сверит данные сервера.
                setCheck('error')
                setCheckedBaseId('')
                setError('Сервер вернул идентификатор другой базы. Новое удаление заблокировано.')
                return
            }
            setOperation(accepted)
            setAcceptedDuringDialog(true)
            requestKey.current = null
            clearKnowledgeDeletionRequestKey('base', baseId, baseId, requestVersion.current ?? base.version)
            requestVersion.current = null
        } catch (cause) {
            if (scope.current !== baseId) return
            const found = await reconcile(baseId)
            if (!found) setError(getApiErrorMessage(cause,
                'Не удалось подтвердить удаление. Проверьте историю перед повтором.'))
        } finally {
            inFlight.current = false
            setBusy(false)
        }
    }

    const modalState = operation ? 'operation' : check === 'checking' ? 'checking'
        : check === 'enabled' && checkedBaseId === base.id ? 'confirm' : 'unavailable'

    return <>
        <button type="button" className="secondary-button" onClick={() => setOpen(true)}
            title={base.lifecycleState === 'DELETING'
                ? 'Посмотреть состояние ранее принятого удаления' : 'Открыть управление удалением базы'}>
            {base.lifecycleState === 'DELETING' ? 'Статус удаления…' : 'Удалить базу…'}
        </button>
        {open && <>
            {modalState === 'checking' && <KnowledgeErasureDialog mode="checking" kind="base"
                entityName={base.name} onClose={close}/>}
            {modalState === 'unavailable' && <KnowledgeErasureDialog mode="unavailable" kind="base"
                entityName={base.name} onClose={close} checkFailed={check === 'error'}
                reason={error || 'Удаление баз знаний сейчас недоступно.'}/>}
            {modalState === 'confirm' && <KnowledgeErasureDialog mode="confirm" kind="base"
                entityName={base.name} busy={busy} onClose={close}
                typedName={name} onTypedNameChange={setName}
                error={error} onConfirm={() => { void confirm() }}/>}
            {modalState === 'operation' && operation && <KnowledgeErasureDialog mode="operation" kind="base"
                entityName={base.name} onClose={close} operation={operation}
                statusError={statusError} historyHref="/knowledge#knowledge-base-deletion-history"/>}
        </>}
    </>
}
