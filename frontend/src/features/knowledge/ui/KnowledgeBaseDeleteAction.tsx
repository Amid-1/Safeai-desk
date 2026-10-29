// frontend/src/features/knowledge/ui/KnowledgeBaseDeleteAction.tsx
import {useEffect, useRef, useState} from 'react'
import type {KnowledgeBase} from '../../../entities/knowledge/api/knowledgeApi'
import {acceptBaseDeletion, baseDeletionCapability} from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'
import {getApiErrorMessage} from '../../../shared/api/http'
import {createSecureUuid} from '../../../shared/lib/secureUuid'
import Modal from '../../../shared/ui/Modal'

export function KnowledgeBaseDeleteAction({base, onAccepted}: {
    base: KnowledgeBase
    onAccepted: () => void
}) {
    const [open, setOpen] = useState(false)
    const [allowed, setAllowed] = useState(false)
    const [loading, setLoading] = useState(false)
    const [busy, setBusy] = useState(false)
    const [name, setName] = useState('')
    const [reason, setReason] = useState('')
    const requestKey = useRef<string | null>(null)
    const inFlight = useRef(false)

    useEffect(() => {
        if (!open) return
        const controller = new AbortController()
        setLoading(true)
        setAllowed(false)
        void baseDeletionCapability(base.id, controller.signal)
            .then(value => {
                if (controller.signal.aborted) return
                setAllowed(value)
                if (!value) setReason('Безвозвратное удаление базы пока не включено на сервере.')
                else setReason('')
            })
            .catch(error => {
                if (!controller.signal.aborted) setReason(getApiErrorMessage(error,
                    'Не удалось проверить возможность удаления базы.'))
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
        if (inFlight.current || !allowed || name !== base.name) return
        inFlight.current = true
        setBusy(true)
        setReason('')
        try {
            if (!requestKey.current) requestKey.current = createSecureUuid()
            await acceptBaseDeletion(base.id, base.version, requestKey.current)
            closeAfterAccepted()
            onAccepted()
        } catch (error) {
            // Preserve the same key on timeout: repeating the request must be idempotent.
            setReason(getApiErrorMessage(error,
                'Не удалось подтвердить удаление. Проверьте состояние операции перед повтором.'))
        } finally {
            inFlight.current = false
            setBusy(false)
        }
    }
    function closeAfterAccepted() {
        setOpen(false)
        setName('')
        requestKey.current = null
    }

    return <>
        <button type="button" className="secondary-button"
            disabled={base.lifecycleState === 'DELETING'}
            title={base.lifecycleState === 'DELETING'
                ? 'База уже находится в процессе удаления' : 'Безвозвратно удалить базу знаний'}
            onClick={() => setOpen(true)}>Удалить базу…</button>
        {open && <Modal title="Удалить базу знаний?" size="md"
            onClose={close} closeDisabled={busy}>
            <p>Безвозвратно удалить базу «{base.name}» и все её документы, версии,
                исходные файлы и поисковые индексы? История действий сохраняется
                в соответствии с политикой хранения.</p>
            <p><strong>Операция необратима.</strong> Для подтверждения введите точное название базы.</p>
            <label>Название базы
                <input type="text" autoComplete="off" maxLength={255}
                    value={name} disabled={busy}
                    onChange={event => setName(event.target.value)}/>
            </label>
            {reason && <p role="alert">{reason}</p>}
            <div className="modal-actions">
                <button type="button" className="secondary-button" disabled={busy}
                    onClick={close}>Отмена</button>
                <button type="button" className="danger-button"
                    disabled={busy || loading || !allowed || name !== base.name}
                    onClick={() => { void confirm() }}>
                    {busy ? 'Отправляем запрос…' : 'Удалить базу безвозвратно'}
                </button>
            </div>
        </Modal>}
    </>
}
