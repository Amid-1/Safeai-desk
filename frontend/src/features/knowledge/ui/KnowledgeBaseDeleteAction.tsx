// frontend/src/features/knowledge/ui/KnowledgeBaseDeleteAction.tsx
import {useEffect, useId, useRef, useState} from 'react'
import type {KnowledgeBase} from '../../../entities/knowledge/api/knowledgeApi'
import {acceptBaseDeletion, baseDeletionCapability} from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'
import {getApiErrorMessage} from '../../../shared/api/http'
import {createSecureUuid} from '../../../shared/lib/secureUuid'
import Modal from '../../../shared/ui/Modal'
import {matchesKnowledgeDeletionName} from '../lib/knowledgeDeletionConfirmation'
import '../styles/KnowledgeDeletionConfirmation.css'

export function KnowledgeBaseDeleteAction({base, onAccepted}: {
    base: KnowledgeBase
    onAccepted: () => void
}) {
    const [open, setOpen] = useState(false)
    const [allowed, setAllowed] = useState(false)
    const [checkedBaseId, setCheckedBaseId] = useState('')
    const [loading, setLoading] = useState(false)
    const [busy, setBusy] = useState(false)
    const [name, setName] = useState('')
    const [reason, setReason] = useState('')
    const [checkFailed, setCheckFailed] = useState(false)
    const nameInputId = useId()
    const requestKey = useRef<string | null>(null)
    const inFlight = useRef(false)
    const canSubmit = allowed && checkedBaseId === base.id
        && matchesKnowledgeDeletionName(name, base.name)
        && !loading && !busy && base.lifecycleState !== 'DELETING'

    useEffect(() => {
        if (!open) return
        const controller = new AbortController()
        setLoading(true)
        setAllowed(false)
        setCheckedBaseId('')
        setName('')
        setReason('')
        setCheckFailed(false)
        requestKey.current = null
        void baseDeletionCapability(base.id, controller.signal)
            .then(value => {
                if (controller.signal.aborted) return
                setAllowed(value)
                setCheckFailed(false)
                setCheckedBaseId(base.id)
                setReason(value ? '' : 'Удаление баз знаний пока отключено в настройках системы.')
            })
            .catch(error => {
                if (!controller.signal.aborted) {
                    setCheckFailed(true)
                    setReason(getApiErrorMessage(error,
                        'Не удалось проверить возможность удаления базы.'))
                }
            })
            .finally(() => { if (!controller.signal.aborted) setLoading(false) })
        return () => controller.abort()
    }, [open, base.id])

    function close() {
        if (inFlight.current) return
        setOpen(false)
        setName('')
        setReason('')
        requestKey.current = null
    }

    async function confirm() {
        if (inFlight.current || !canSubmit) return
        inFlight.current = true
        setBusy(true)
        setReason('')
        try {
            if (!requestKey.current) requestKey.current = createSecureUuid()
            await acceptBaseDeletion(base.id, base.version, requestKey.current)
            setOpen(false)
            setName('')
            requestKey.current = null
            onAccepted()
        } catch (error) {
            // Повторяем запрос с тем же ключом: результат предыдущей попытки мог быть неизвестен.
            setReason(getApiErrorMessage(error,
                'Не удалось подтвердить удаление. Проверьте состояние операции перед повтором.'))
        } finally {
            inFlight.current = false
            setBusy(false)
        }
    }

    return <>
        <button type="button" className="secondary-button"
            disabled={base.lifecycleState === 'DELETING'}
            title={base.lifecycleState === 'DELETING'
                ? 'База уже находится в процессе удаления' : 'Безвозвратно удалить базу знаний'}
            onClick={() => setOpen(true)}>Удалить базу…</button>
        {open && <Modal title={loading ? 'Проверяем удаление базы'
            : allowed && checkedBaseId === base.id ? 'Удалить базу знаний?'
                : checkFailed ? 'Не удалось проверить удаление' : 'Удаление базы недоступно'} size="md"
            onClose={close} closeDisabled={busy}>
            {loading && <p role="status">Проверяем, доступно ли удаление…</p>}
            {!loading && !allowed && <>
                <p className="knowledge-deletion-confirmation__notice" role="status">
                    {reason || 'Система не разрешила удаление базы знаний.'}
                </p>
                {checkFailed ? <p>Повторите попытку позже. Пока доступность удаления
                    не подтверждена, файлы останутся на месте.</p>
                    : <p>Чтобы воспользоваться этой функцией, её необходимо включить и проверить.</p>}
            </>}
            {!loading && allowed && checkedBaseId === base.id && <>
                <p>Вы собираетесь удалить базу «{base.name}», все её документы,
                    сохранённые версии файлов и поисковые данные. Восстановить их
                    обычным способом после завершения удаления будет нельзя.</p>
                <p>После подтверждения система начнёт удаление. Если файлов много,
                    это может занять некоторое время. Результат можно проверить
                    в истории удаления. Записи о выполненных действиях сохраняются.</p>
            </>}
            {!loading && allowed && reason && <p role="alert"
                className="knowledge-deletion-confirmation__notice">{reason}</p>}
            {!loading && allowed && checkedBaseId === base.id && <div
                className="knowledge-deletion-confirmation__field">
                <label htmlFor={nameInputId}>Название базы знаний</label>
                <input id={nameInputId} type="text" autoComplete="off" maxLength={255}
                    value={name} disabled={busy} spellCheck={false}
                    onChange={event => setName(event.target.value)}/>
                <p className="knowledge-deletion-confirmation__hint">
                    Введите полное название. Регистр не важен, но латинские и
                    кириллические буквы, а также цифры должны совпадать.
                </p>
            </div>}
            <div className="modal-actions">
                <button type="button" className="secondary-button" disabled={busy}
                    onClick={close}>{allowed ? 'Отмена' : 'Закрыть'}</button>
                {allowed && checkedBaseId === base.id && <button
                    type="button" className="danger-button" disabled={!canSubmit}
                    onClick={() => { void confirm() }}>
                    {busy ? 'Отправляем запрос…' : 'Подтвердить удаление базы'}
                </button>}
            </div>
        </Modal>}
    </>
}
