// frontend/src/features/erasure/ui/ErasureRequestsWorkspace.tsx
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getChats, type Chat } from '../../chat/api/chatApi'
import { getKnowledgeBases } from '../../../entities/knowledge/api/knowledgeApi'
import type { KnowledgeBase } from '../../../entities/knowledge/api/knowledgeApi'
import { getKnowledgeDocuments, type KnowledgeDocument } from '../../../entities/knowledge/api/knowledgeDocumentApi'
import { getApiErrorMessage } from '../../../shared/api/http'
import { EmptyState, LoadingState } from '../../../shared/ui/StateBlock'
import {
    createDraft, loadMine, previewCase, submitDraft, withdrawCase,
    type CaseView, type Preview, type ResourceType, type Target,
} from '../api/erasureApi'
import './ErasureRequestsWorkspace.css'

const TYPES: Record<ResourceType, { title: string; operation: Target['operation']; scope: string }> = {
    USER_ACCOUNT: { title: 'Моя учётная запись', operation: 'DELETE_ACCOUNT',
        scope: 'PERSONAL_DATA_AND_ACCESS' },
    CHAT: { title: 'Мой чат', operation: 'REDACT_CONTENT', scope: 'FULL_CHAT_CONTENT' },
    KNOWLEDGE_DOCUMENT: { title: 'Документ', operation: 'DELETE_OBJECT', scope: 'FULL_DOCUMENT' },
    KNOWLEDGE_BASE: { title: 'База знаний', operation: 'DELETE_OBJECT', scope: 'FULL_BASE' },
    ORGANIZATION: { title: 'Моя организация', operation: 'OFFBOARD_TENANT', scope: 'TENANT_CLOSURE' },
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const STATE_LABELS: Record<CaseView['state'], string> = {
    DRAFT: 'Черновик, не отправлен',
    SUBMITTED: 'Заявка зарегистрирована',
    WITHDRAWN: 'Заявка отозвана',
}

type Props = { user: { id: string; organizationId: string; roles: string[] } }

export function ErasureRequestsWorkspace({ user }: Props) {
    const [items, setItems] = useState<CaseView[]>([])
    const [type, setType] = useState<ResourceType>('USER_ACCOUNT')
    const [resourceId, setResourceId] = useState('')
    const [knowledgeBaseId, setKnowledgeBaseId] = useState('')
    const [chats, setChats] = useState<Chat[]>([])
    const [bases, setBases] = useState<KnowledgeBase[]>([])
    const [documents, setDocuments] = useState<KnowledgeDocument[]>([])
    const [pickerError, setPickerError] = useState<string | null>(null)
    const [preview, setPreview] = useState<Preview | null>(null)
    const [pending, setPending] = useState(false)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    const [notice, setNotice] = useState<string | null>(null)
    const isAdmin = user.roles.includes('ADMIN')

    const reload = useCallback(async (signal?: AbortSignal) => {
        setLoading(true)
        try {
            const rows = await loadMine(signal)
            if (!signal?.aborted) { setItems(rows); setError(null) }
        } catch (err) {
            if (!signal?.aborted) setError(getApiErrorMessage(err, 'Не удалось выполнить запрос.'))
        } finally {
            if (!signal?.aborted) setLoading(false)
        }
    }, [])

    useEffect(() => {
        const controller = new AbortController()
        void reload(controller.signal)
        return () => controller.abort()
    }, [reload])

    useEffect(() => {
        const ac = new AbortController()
        setPickerError(null)
        setChats([]); setBases([]); setDocuments([])
        if (type === 'CHAT') {
            void getChats(0, 100, { signal: ac.signal })
                .then(data => { if (!ac.signal.aborted) setChats(data.content) })
                .catch(err => { if (!ac.signal.aborted) setPickerError(getApiErrorMessage(err,
                    'Не удалось загрузить чаты.')) })
        } else if (type === 'KNOWLEDGE_DOCUMENT' || type === 'KNOWLEDGE_BASE') {
            void getKnowledgeBases(0, 100, { signal: ac.signal })
                .then(data => { if (!ac.signal.aborted) setBases(data.content) })
                .catch(err => { if (!ac.signal.aborted) setPickerError(getApiErrorMessage(err,
                    'Не удалось загрузить базы знаний.')) })
        }
        return () => ac.abort()
    }, [type])

    useEffect(() => {
        if (type !== 'KNOWLEDGE_DOCUMENT' || !knowledgeBaseId) return
        const ac = new AbortController()
        void getKnowledgeDocuments(knowledgeBaseId, 0, 100, ac.signal)
            .then(data => { if (!ac.signal.aborted) setDocuments(data.content) })
            .catch(err => { if (!ac.signal.aborted) setPickerError(getApiErrorMessage(err,
                'Не удалось загрузить документы.')) })
        return () => ac.abort()
    }, [type, knowledgeBaseId])

    const selected = TYPES[type]
    const targetId = type === 'USER_ACCOUNT' ? user.id
        : type === 'ORGANIZATION' ? user.organizationId : resourceId.trim()
    const valid = UUID.test(targetId)
    const target: Target = {
        resourceType: type, resourceId: targetId,
        operation: selected.operation, scope: selected.scope,
    }

    async function run(action: () => Promise<void>) {
        setPending(true); setError(null); setNotice(null)
        try { await action() } catch (err) { setError(getApiErrorMessage(err, 'Не удалось выполнить запрос.')) }
        finally { setPending(false) }
    }

    return (
        <section className="erasure-workspace page">
            <header className="erasure-hero">
                <div className="erasure-eyebrow">КОНФИДЕНЦИАЛЬНОСТЬ И ДАННЫЕ</div>
                <h1>Мои запросы на удаление</h1>
                <p>Здесь можно создать заявку, просмотреть её состояние или отозвать.
                    Создание заявки не удаляет данные автоматически.</p>
            </header>
            <div className="erasure-warning" role="note">
                <strong>Исполнение пока не включено.</strong>
                <span> На этом этапе система только регистрирует запросы. Ни чат,
                    ни документ, ни учётная запись не будут физически удалены.</span>
            </div>
            <div className="erasure-columns">
                <section className="erasure-panel" aria-labelledby="new-case-title">
                    <h2 id="new-case-title">Новая заявка</h2>
                    <p className="erasure-muted">Выберите объект и проверьте последствия.</p>
                    <label htmlFor="erasure-resource-type">Что вы хотите удалить</label>
                    <select id="erasure-resource-type" value={type} disabled={pending}
                        onChange={event => {
                            setType(event.target.value as ResourceType)
                            setPreview(null); setResourceId(''); setKnowledgeBaseId(''); setNotice(null)
                        }}>
                        {Object.entries(TYPES).filter(([key]) =>
                            key !== 'ORGANIZATION' || isAdmin).map(([key, item]) => (
                            <option key={key} value={key}>{item.title}</option>
                        ))}
                    </select>
                    {type === 'CHAT' && <>
                        <label htmlFor="erasure-chat-select">Мой чат</label>
                        <select id="erasure-chat-select" disabled={pending} value={resourceId}
                            onChange={e => { setResourceId(e.target.value); setPreview(null) }}>
                            <option value="">Выберите чат</option>
                            {chats.map(chat => <option key={chat.id} value={chat.id}>
                                {chat.title || 'Без названия'}
                            </option>)}
                        </select>
                        <p className="erasure-muted">Показаны первые 100 доступных чатов.</p>
                    </>}
                    {(type === 'KNOWLEDGE_BASE' || type === 'KNOWLEDGE_DOCUMENT') && <>
                        <label htmlFor="erasure-base-select">База знаний</label>
                        <select id="erasure-base-select" value={type === 'KNOWLEDGE_BASE' ? resourceId : knowledgeBaseId}
                            disabled={pending} onChange={e => {
                                if (type === 'KNOWLEDGE_BASE') setResourceId(e.target.value)
                                else { setKnowledgeBaseId(e.target.value); setResourceId(''); setDocuments([]) }
                                setPreview(null)
                            }}>
                            <option value="">Выберите базу знаний</option>
                            {bases.map(base => <option key={base.id} value={base.id}>{base.name}</option>)}
                        </select>
                    </>}
                    {type === 'KNOWLEDGE_DOCUMENT' && knowledgeBaseId && <>
                        <label htmlFor="erasure-doc-select">Документ</label>
                        <select id="erasure-doc-select" disabled={pending} value={resourceId}
                            onChange={e => { setResourceId(e.target.value); setPreview(null) }}>
                            <option value="">Выберите документ</option>
                            {documents.map(doc => <option key={doc.id} value={doc.id}>{doc.name}</option>)}
                        </select>
                        <p className="erasure-muted">Показаны первые 100 доступных документов.</p>
                    </>}
                    {pickerError && <div role="alert" className="erasure-error">{pickerError}</div>}
                    <div className="erasure-intent">
                        <strong>{selected.title}</strong>
                        <span>Будет создан запрос на рассмотрение.
                            До одобрения и подключения обработчика стирание невозможно.</span>
                    </div>
                    {!preview && (
                        <button type="button" disabled={!valid || pending}
                            onClick={() => void run(async () => {
                                setPreview(await previewCase(target))
                                setNotice('Объект проверен. Подтвердите создание черновика.')
                            })}>
                            Проверить запрос
                        </button>
                    )}
                    {preview && (
                        <div className="erasure-confirm">
                            <p><strong>Предпросмотр готов.</strong> Действует до {
                                new Date(preview.expiresAt).toLocaleString('ru-RU')}.</p>
                            <p>Проверка не разрешает удаление и не гарантирует его исполнение.</p>
                            <button type="button" disabled={pending}
                                onClick={() => void run(async () => {
                                    await createDraft(target, preview,
                                        type === 'USER_ACCOUNT' ? 'SELF_REQUEST'
                                            : type === 'ORGANIZATION' ? 'ORGANIZATION_REQUEST'
                                            : 'OWNER_REQUEST')
                                    setPreview(null); setResourceId('')
                                    await reload()
                                    setNotice('Черновик создан. Чтобы подать заявку, нажмите «Подать» ниже.')
                                })}>
                                Создать черновик
                            </button>
                        </div>
                    )}
                </section>
                <section className="erasure-panel" aria-labelledby="case-list-title">
                    <div className="erasure-panel-heading">
                        <h2 id="case-list-title">История моих заявок</h2>
                        <button type="button" className="erasure-secondary"
                            disabled={pending || loading} onClick={() => void reload()}>
                            Обновить
                        </button>
                    </div>
                    {loading ? <LoadingState message="Загружаем заявки..." />
                        : items.length === 0 ? <EmptyState message="Заявок пока нет." />
                            : <ul className="erasure-list">{items.map(item => (
                                <li key={item.requestId}>
                                    <div className="erasure-case-head">
                                        <strong>{TYPES[item.resourceType]?.title ?? item.resourceType}</strong>
                                        <span className="erasure-state">{STATE_LABELS[item.state]}</span>
                                    </div>
                                    <small>Создана: {new Date(item.createdAt).toLocaleString('ru-RU')}</small>
                                    {item.state === 'SUBMITTED' && <Link
                                        to={`/my/erasure-requests/${item.requestId}`}>
                                        История рассмотрения
                                    </Link>}
                                    <div className="erasure-actions">
                                        {item.state === 'DRAFT' && (
                                            <button type="button" disabled={pending}
                                                onClick={() => void run(async () => {
                                                    await submitDraft(item)
                                                    await reload()
                                                    setNotice('Заявка зарегистрирована. Удаление не начато.')
                                                })}>Подать заявку</button>
                                        )}
                                        {item.state !== 'WITHDRAWN' && (
                                            <button type="button" className="erasure-secondary"
                                                disabled={pending}
                                                onClick={() => void run(async () => {
                                                    await withdrawCase(item); await reload()
                                                    setNotice('Заявка отозвана.')
                                                })}>Отозвать</button>
                                        )}
                                    </div>
                                </li>
                            ))}</ul>}
                </section>
            </div>
            {error && <div role="alert" className="erasure-error">{error}</div>}
            {notice && <div role="status" className="erasure-notice">{notice}</div>}
            {isAdmin && <p className="erasure-footer">
                Для заявок сотрудников предусмотрена отдельная очередь администратора.
            </p>}
        </section>
    )
}
