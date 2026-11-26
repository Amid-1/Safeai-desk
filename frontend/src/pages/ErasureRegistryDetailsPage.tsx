// frontend/src/pages/ErasureRegistryDetailsPage.tsx
import { useEffect, useState } from 'react'
import { useOutletContext, useParams } from 'react-router-dom'
import { loadRegistryDetail, type Capabilities, type RegistryDetail } from '../features/erasure/api/workspaceApi'
import { RESOURCE_NAMES, registryState, reviewTime } from '../features/erasure/review/reviewLabels'
import { WorkspaceReturn } from '../shared/ui/WorkspaceReturn'
import '../features/erasure/review/ErasureReview.css'

export default function ErasureRegistryDetailsPage() {
    const { requestId = '' } = useParams()
    const workspace = useOutletContext<{capabilities:Capabilities|null;loading:boolean;failed:boolean}|null>()
    const platform = workspace?.capabilities?.platform === true
    const allowed = platform || workspace?.capabilities?.tenantRegistry === true
    const validId = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(requestId)
    const [data, setData] = useState<RegistryDetail|null>(null)
    const [error, setError] = useState(false)
    const [revision, setRevision] = useState(0)
    useEffect(() => {
        const controller = new AbortController()
        setData(null); setError(false)
        if (allowed && validId) {
            void loadRegistryDetail(requestId, controller.signal).then(value => {
                if (!controller.signal.aborted) setData(value)
            }).catch(() => { if (!controller.signal.aborted) setError(true) })
        }
        return () => controller.abort()
    }, [requestId, allowed, validId, revision, platform])
    // Discard previously loaded data immediately on scope or ID changes.
    const item = allowed && data?.requestId === requestId && data.reviewVisible === platform ? data : null
    return <section className="er2-page page er2-registry-detail">
        <WorkspaceReturn fallback={platform ? '/admin/erasure/registry' : allowed ? '/admin/erasure/intake' : '/admin/erasure'} />
        <header className="er2-heading"><h1>Карточка заявки</h1></header>
        {!allowed ? <p role="status">{workspace?.loading ? 'Проверяем доступ…' : 'Карточка недоступна.'}</p>
            : !validId ? <p role="alert">Заявка не найдена.</p>
            : error ? <div role="alert" className="er2-card">Не удалось открыть заявку. <button className="er2-action" type="button" onClick={() => setRevision(v=>v+1)}>Повторить</button></div>
            : !item ? <p role="status">Загрузка…</p>
            : <div className="er2-card"><h2>{RESOURCE_NAMES[item.resourceType] ?? 'Объект'}</h2>
                <dl className="er2-summary">
                    <div><dt>Номер заявки</dt><dd>{item.requestId}</dd></div>
                    <div><dt>Организация</dt><dd>{item.organization}</dd></div>
                    <div><dt>Регистрация</dt><dd>{registryState(item.intakeState)}</dd></div>
                    {item.reviewVisible && <><div><dt>Рассмотрение</dt><dd>{item.reviewState ? registryState(item.reviewState) : 'Не начато'}</dd></div>
                        <div><dt>Юридическое удержание</dt><dd>{item.holdActive ? 'Активно' : 'Нет активного удержания'}</dd></div></>}
                    <div><dt>Создана</dt><dd>{reviewTime(item.createdAt)}</dd></div>
                    <div><dt>Обновлена</dt><dd>{reviewTime(item.updatedAt)}</dd></div>
                    <div><dt>Удаление по заявке</dt><dd>Не подключено</dd></div>
                    <div><dt>Результат удаления</dt><dd>Не подтверждён</dd></div>
                </dl><p className="er2-muted">Карточка предназначена для просмотра. Согласование не запускает удаление данных.</p>
            </div>}
    </section>
}
