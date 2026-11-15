// frontend/src/features/chat/controller/ChatArchiveModal.tsx
import {useCallback, useEffect, useRef, useState} from 'react'
import Modal from '../../../shared/ui/Modal'
import {useAuth} from '../../auth/state/useAuth'
import {ApiError, getApiErrorMessage} from '../../../shared/api/http'
import {createSecureUuid} from '../../../shared/lib/secureUuid'
import type {Chat, ChatDetails} from '../api/chatApi'
import {
    getArchivedChats, getArchivedChatDetails, restoreChat,
    getChatErasureCapability, getChatErasureOperation, listChatErasureOperations,
    requestChatContentErasure,
} from '../api/chat/chatArchiveClient'
import type {ChatErasureCapability, ChatErasureOperation} from '../api/chat/chatArchiveClient'
import './ChatArchiveModal.css'

type Props = { onClose: () => void; onChanged: () => void }

const TERMINAL = new Set(['ERASED', 'NEEDS_REVIEW', 'BLOCKED_BY_RETENTION', 'CANCELLED'])
const POLL_INTERVAL_MS = 12_000

/**
 * Treat only the explicitly expected HTTP status as an empty result.
 * All other failures continue to propagate to the calling error handler.
 */
async function nullOnExpectedHttpStatus<T>(
    request: Promise<T>, expectedStatus: number,
): Promise<T | null> {
    try {
        return await request
    } catch (cause) {
        if (cause instanceof ApiError && cause.status === expectedStatus) return null
        throw cause
    }
}

export default function ChatArchiveModal({onClose, onChanged}: Props) {
    const {currentUser} = useAuth()
    const canErase = currentUser?.roles.includes('ADMIN') ?? false
    const [items, setItems] = useState<Chat[]>([])
    const [page, setPage] = useState(0)
    const [hasNext, setHasNext] = useState(false)
    const [loading, setLoading] = useState(true)
    const [selected, setSelected] = useState<ChatDetails | null>(null)
    const [capability, setCapability] = useState<ChatErasureCapability | null>(null)
    const [operation, setOperation] = useState<ChatErasureOperation | null>(null)
    const [operations, setOperations] = useState<ChatErasureOperation[]>([])
    const [confirming, setConfirming] = useState(false)
    const [confirmationTitle, setConfirmationTitle] = useState('')
    const [confirmationPhrase, setConfirmationPhrase] = useState('')
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')
    const abortRef = useRef<AbortController | null>(null)
    const requestKeys = useRef(new Map<string, string>())
    const selectedId = selected?.id

    const refresh = useCallback(async (signal?: AbortSignal) => {
        const response = await getArchivedChats(0, 50, signal)
        if (signal?.aborted) return
        setItems(response.content)
        setPage(response.page)
        setHasNext(response.hasNext)
        if (!canErase) return
        const history = await nullOnExpectedHttpStatus(listChatErasureOperations(signal), 403)
        if (!signal?.aborted && history !== null) setOperations(history)
    }, [canErase])

    useEffect(() => {
        const abort = new AbortController()
        void refresh(abort.signal).catch((cause) => {
            if (!abort.signal.aborted) setError(getApiErrorMessage(cause, 'Не удалось загрузить архив.'))
        }).finally(() => {
            if (!abort.signal.aborted) setLoading(false)
        })
        return () => {
            abort.abort();
            abortRef.current?.abort()
        }
    }, [refresh])

    useEffect(() => {
        if (!selectedId || operation?.state !== 'ERASING') return
        const abort = new AbortController()
        const tick = async () => {
            try {
                const updated = await getChatErasureOperation(selectedId, abort.signal)
                if (abort.signal.aborted) return
                setOperation(updated)
                if (updated.state === 'CANCELLED') {
                    setSelected(null)
                    setOperation(null)
                }
                if (updated.state === 'ERASED') {
                    setSelected(current => current?.id === updated.chatId
                        ? {...current, title: '[СОДЕРЖИМОЕ УДАЛЕНО]', messages: []} : current)
                }
                if (TERMINAL.has(updated.state)) {
                    await refresh(abort.signal)
                    onChanged()
                }
            } catch (cause) {
                if (!abort.signal.aborted) {
                    setError(getApiErrorMessage(cause,
                        'Не удалось проверить состояние стирания. Не запускайте новую операцию.'))
                }
            }
        }
        const id = window.setInterval(() => {
            void tick()
        }, POLL_INTERVAL_MS)
        return () => {
            abort.abort();
            window.clearInterval(id)
        }
    }, [selectedId, operation?.state, refresh, onChanged])

    async function openChat(chat: Chat) {
        abortRef.current?.abort()
        const controller = new AbortController()
        abortRef.current = controller
        setSelected(null)
        setCapability(null)
        setConfirming(false)
        setOperation(null)
        setError('')
        setBusy(true)
        try {
            const detail = await getArchivedChatDetails(chat.id, controller.signal)
            if (!controller.signal.aborted) setSelected(detail)
        } catch (cause) {
            if (!controller.signal.aborted) {
                setError(getApiErrorMessage(cause, 'Не удалось открыть архивированный чат.'))
            }
        } finally {
            if (!controller.signal.aborted) setBusy(false)
        }
    }

    async function more() {
        setBusy(true)
        setError('')
        try {
            const response = await getArchivedChats(page + 1, 50)
            setItems(previous => [...previous,
                ...response.content.filter(item => !previous.some(existing => existing.id === item.id))])
            setPage(response.page)
            setHasNext(response.hasNext)
        } catch (cause) {
            setError(getApiErrorMessage(cause, 'Не удалось загрузить следующую страницу архива.'))
        } finally {
            setBusy(false)
        }
    }

    async function restore() {
        if (!selected || busy || operation) return
        setBusy(true)
        setError('')
        try {
            await restoreChat(selected.id)
            await refresh()
            setSelected(null)
            onChanged()
        } catch (cause) {
            setError(getApiErrorMessage(cause, 'Не удалось восстановить чат.'))
        } finally {
            setBusy(false)
        }
    }

    async function inspectErasure() {
        if (!selected || busy || !canErase) return
        setBusy(true)
        setError('')
        try {
            // A previous ambiguous request must be reconciled before a new intent.
            let prior = await nullOnExpectedHttpStatus(getChatErasureOperation(selected.id), 404)
            if (prior && prior.state === 'CANCELLED') {
                // A cancelled operation preserves the archive and permits a new
                // explicit request after a fresh server capability check.
                requestKeys.current.delete(selected.id)
                prior = null
            }
            if (prior) {
                setSelected(current => current?.id === prior.chatId
                    ? {...current, title: '[СОДЕРЖИМОЕ УДАЛЕНО]', messages: []} : current)
                setOperation(prior)
                setError('Уже существует операция стирания. Новый запрос не создавался.')
                return
            }
            const current = await getChatErasureCapability(selected.id)
            setCapability(current)
            if (current.allowed) {
                setConfirmationTitle('')
                setConfirmationPhrase('')
                setConfirming(true)
            } else {
                setError(`Удаление недоступно: ${current.reason}.`)
            }
        } catch (cause) {
            setError(getApiErrorMessage(cause, 'Проверка стирания не завершена. Удаление заблокировано.'))
        } finally {
            setBusy(false)
        }
    }

    async function requestErasure() {
        if (!canErase || !selected || !capability?.allowed || busy
            || confirmationTitle !== selected.title || confirmationPhrase !== 'УДАЛИТЬ НАВСЕГДА') return
        setBusy(true)
        setError('')
        try {
            const current = await getChatErasureCapability(selected.id)
            if (!current.allowed || current.version !== capability.version) {
                // No erasure request has been sent. Force explicit reconfirmation
                // instead of throwing into the ambiguous-response recovery path.
                setCapability(null)
                setConfirming(false)
                setConfirmationTitle('')
                setConfirmationPhrase('')
                setError('Чат изменился или удаление стало недоступно. Обновите проверку.')
                return
            }
            const key = requestKeys.current.get(selected.id) ?? createSecureUuid()
            requestKeys.current.set(selected.id, key)
            const accepted = await requestChatContentErasure(
                selected.id, selected.title, current.version, key,
            )
            setOperation(accepted)
            setSelected(current => current?.id === accepted.chatId
                ? {...current, title: '[СОДЕРЖИМОЕ УДАЛЕНО]', messages: []} : current)
            setConfirmationTitle('')
            setConfirmationPhrase('')
            setConfirming(false)
            await refresh()
            onChanged()
        } catch (cause) {
            // Unknown response is NOT a failed erasure: never generate a new key.
            setError(getApiErrorMessage(cause,
                'Результат запроса неизвестен. Проверьте состояние на сервере перед повтором.'))
            try {
                const current = await getChatErasureOperation(selected.id)
                setOperation(current)
                setSelected(previous => previous?.id === current.chatId
                    ? {...previous, title: '[СОДЕРЖИМОЕ УДАЛЕНО]', messages: []} : previous)
                setConfirmationTitle('')
                setConfirmationPhrase('')
                setConfirming(false)
            } catch {
                // Preserve the original key and require explicit reconciliation.
            }
        } finally {
            setBusy(false)
        }
    }

    return (
        <Modal title="Архив чатов" size="lg" onClose={onClose} closeDisabled={busy}>
            <div className="chat-archive">
                <p className="muted">Архивирование скрывает чат из рабочего списка, но сохраняет историю.
                    Восстановление возвращает его обратно.</p>
                {error && <p role="alert" className="error">{error}</p>}
                {operations.length > 0 && <section className="chat-archive__operations">
                    <h3>История стирания</h3>
                    {operations.map(item => <p key={item.operationId}>
                        Чат {item.chatId.slice(0, 8)}: {item.state}
                    </p>)}
                </section>}
                {loading ? <p>Загрузка архива…</p> : (
                    <div className="chat-archive__layout">
                        <div className="chat-archive__list" aria-label="Архивированные чаты">
                            {items.length === 0 && <p>Архив пуст.</p>}
                            {items.map(item => (
                                <button key={item.id} type="button" disabled={busy}
                                        className={selected?.id === item.id ? 'secondary-button active' : 'secondary-button'}
                                        onClick={() => void openChat(item)}>
                                    {item.title}
                                </button>
                            ))}
                            {hasNext && <button type="button" disabled={busy}
                                                onClick={() => void more()}>Загрузить ещё</button>}
                        </div>
                        <div className="chat-archive__details">
                            {!selected && <p className="muted">Выберите чат для просмотра.</p>}
                            {selected && <>
                                <h3>{selected.title}</h3>
                                <div className="chat-archive__messages">
                                    {selected.messages.length === 0 && <p>Сообщений нет.</p>}
                                    {selected.messages.map(message => (
                                        <div key={message.id} className="chat-archive__message">
                                            <strong>{message.role === 'USER' ? 'Вы' : 'SafeAI'}</strong>
                                            <p>{message.content}</p>
                                        </div>
                                    ))}
                                </div>
                                {operation ? <div role="status">
                                    <strong>Состояние стирания: {operation.state}</strong>
                                    {operation.state === 'ERASING' &&
                                        <p>Содержимое стирается на сервере. Не запускайте повтор.</p>}
                                    {operation.state === 'ERASED' &&
                                        <p>Содержимое уничтожено. Аудиторские и финансовые метаданные сохранены.</p>}
                                    {operation.state === 'NEEDS_REVIEW' && <p>Нужна проверка администратором.</p>}
                                    {operation.state === 'BLOCKED_BY_RETENTION' &&
                                        <p>Операция заблокирована политикой хранения.</p>}
                                    {operation.state === 'CANCELLED' &&
                                        <p>Заявка отменена уполномоченным оператором. Содержимое не стиралось.</p>}
                                </div> : <div className="chat-archive__actions">
                                    <button type="button" disabled={busy || confirming}
                                            onClick={() => void restore()}>Восстановить чат
                                    </button>
                                    {canErase && <button type="button" className="secondary-button" disabled={busy}
                                                         onClick={() => void inspectErasure()}>Стереть содержимое
                                        навсегда…</button>}
                                </div>}
                                {confirming && !operation && <div className="chat-archive__erase">
                                    <h4>Необратимое стирание содержимого</h4>
                                    <p>Текст сообщений, название и связанные тексты RAG будут стёрты.
                                        Невосстанавливаемые технические идентификаторы, хеши,
                                        стоимость и доказательства AI-вызовов останутся для аудита.
                                        Удаление данных у внешнего AI-провайдера или из резервных копий
                                        этой операцией не подтверждается.</p>
                                    <label htmlFor="chat-delete-title">Введите точное название чата</label>
                                    <input id="chat-delete-title" autoComplete="off" value={confirmationTitle}
                                           onChange={event => setConfirmationTitle(event.target.value)}/>
                                    <label htmlFor="chat-delete-phrase">Введите УДАЛИТЬ НАВСЕГДА</label>
                                    <input id="chat-delete-phrase" autoComplete="off" value={confirmationPhrase}
                                           onChange={event => setConfirmationPhrase(event.target.value)}/>
                                    <button type="button" disabled={busy || confirmationTitle !== selected.title
                                        || confirmationPhrase !== 'УДАЛИТЬ НАВСЕГДА'}
                                            onClick={() => void requestErasure()}>Подтвердить стирание
                                    </button>
                                    <button type="button" className="secondary-button"
                                            onClick={() => setConfirming(false)}>Отмена
                                    </button>
                                </div>}
                            </>}
                        </div>
                    </div>
                )}
            </div>
        </Modal>
    )
}
