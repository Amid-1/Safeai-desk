// frontend/src/pages/AdminErasureQueuePage.tsx
import { useEffect, useState } from 'react'
import { getApiErrorMessage } from '../shared/api/http'
import { loadTenantQueue, type CaseView } from '../features/erasure/api/erasureApi'
import { ErrorState, EmptyState, LoadingState } from '../shared/ui/StateBlock'
import '../features/erasure/ui/ErasureRequestsWorkspace.css'

const TYPE_LABELS: Record<CaseView['resourceType'], string> = {
    USER_ACCOUNT: 'Учётная запись',
    CHAT: 'Чат',
    KNOWLEDGE_DOCUMENT: 'Документ',
    KNOWLEDGE_BASE: 'База знаний',
    ORGANIZATION: 'Организация',
}
const STATE_LABELS: Record<CaseView['state'], string> = {
    DRAFT: 'Черновик',
    SUBMITTED: 'Заявка подана',
    WITHDRAWN: 'Заявка отозвана',
}

export default function AdminErasureQueuePage() {
    const [items, setItems] = useState<CaseView[]>([])
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    useEffect(() => {
        const ac = new AbortController()
        void loadTenantQueue(ac.signal).then(data => {
            if (!ac.signal.aborted) setItems(data)
        }).catch(err => {
            if (!ac.signal.aborted) setError(getApiErrorMessage(err, 'Не удалось загрузить заявки организации.'))
        }).finally(() => { if (!ac.signal.aborted) setLoading(false) })
        return () => ac.abort()
    }, [])
    return <section className="erasure-workspace page">
        <header className="erasure-hero">
            <div className="erasure-eyebrow">АДМИНИСТРИРОВАНИЕ</div>
            <h1>Запросы организации</h1>
            <p>Очередь обращений вашей организации. Согласование и физическое удаление
                подключаются отдельным безопасным этапом.</p>
        </header>
        <div className="erasure-warning" role="note">Только просмотр. Кнопок согласования и
            запуска удаления на этом этапе нет.</div>
        {loading ? <LoadingState message="Загружаем очередь..." />
            : error ? <ErrorState message={error}/>
                : items.length === 0 ? <EmptyState message="Заявок нет."/>
                    : <div className="erasure-panel"><ul className="erasure-list">
                        {items.map(item => <li key={item.requestId}>
                            <div className="erasure-case-head">
                                <strong>{TYPE_LABELS[item.resourceType] ?? item.resourceType}</strong>
                                <span className="erasure-state">
                                    {STATE_LABELS[item.state] ?? item.state}
                                </span>
                            </div>
                            <small>Создана: {new Date(item.createdAt).toLocaleString('ru-RU')}</small>
                            <details>
                                <summary>Технические сведения о заявке</summary>
                                <small>Заявка: {item.requestId}</small>
                                <small>Объект: {item.resourceId}</small>
                            </details>
                        </li>)}
                    </ul></div>}
    </section>
}
