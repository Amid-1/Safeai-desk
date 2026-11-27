// frontend/src/pages/ErasureRegistryDetailsPage.tsx
import { useEffect, useState } from 'react'
import { useOutletContext, useParams } from 'react-router-dom'
import { loadRegistryDetail, type Capabilities, type RegistryDetail } from '../features/erasure/api/workspaceApi'
import { RESOURCE_NAMES, registryState, reviewTime } from '../features/erasure/review/reviewLabels'
import RequestIdCopy from '../features/erasure/ui/RequestIdCopy'
import DataManagementIcon from '../shared/ui/DataManagementIcon'
import { WorkspaceLink, WorkspaceReturn } from '../shared/ui/WorkspaceReturn'
import '../features/erasure/review/ErasureReview.css'

function nextStep(item: RegistryDetail, approvalsEnabled: boolean | undefined): string {
    if (item.intakeState === 'WITHDRAWN') return 'Заявитель отозвал обращение. Удаление по этой заявке не выполняется.'
    if (item.intakeState === 'DRAFT') return 'Черновик ещё не зарегистрирован. Заявитель должен отправить обращение.'
    if (!item.reviewVisible) return 'Статус рассмотрения недоступен в реестре регистрации организации.'
    if (item.holdActive) return 'Активно юридическое удержание. Решение требует снятия удержания уполномоченным лицом.'
    if (item.reviewState === 'APPROVED_REVIEW_ONLY') return 'Предварительное согласование зафиксировано. Это не разрешение на физическое удаление.'
    if (item.reviewState === 'REJECTED') return 'Решение об отказе зафиксировано. Удаление не запускается.'
    if (approvalsEnabled === false) return 'Рассмотрение заявок отключено. Зарегистрированное обращение сохраняется до включения и настройки рассмотрения.'
    if (item.reviewState === 'NEEDS_INFORMATION') return 'Требуется уточнение со стороны заявителя. Решение ещё не принято.'
    if (item.reviewState === 'PENDING') return 'Ожидается решение независимого уполномоченного сотрудника.'
    if (item.reviewState === null) return 'Заявка зарегистрирована, запись рассмотрения ещё не создана.'
    return 'Текущее состояние требует проверки. Удаление не запускается.'
}

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
        <header className="er2-heading er2-heading--row"><h1>Карточка заявки</h1>
            {platform && item && <WorkspaceLink className="er2-action" to={`/admin/erasure/history?query=${encodeURIComponent(item.requestId)}&page=0`}><DataManagementIcon name="history"/>История этой заявки</WorkspaceLink>}
        </header>
        {!allowed ? <p role="status">{workspace?.loading ? 'Проверяем доступ…' : 'Карточка недоступна.'}</p>
            : !validId ? <p role="alert">Заявка не найдена.</p>
            : error ? <div role="alert" className="er2-card">Не удалось открыть заявку. <button className="er2-action" type="button" onClick={() => setRevision(v=>v+1)}>Повторить</button></div>
            : !item ? <p role="status">Загрузка…</p>
            : <div className="er2-card"><h2>{RESOURCE_NAMES[item.resourceType] ?? 'Объект'}</h2>
                <ol className="er2-registry-lifecycle" aria-label="Жизненный цикл заявки">
                    <li><span>1. Регистрация</span><strong>{item.intakeState === 'SUBMITTED' ? 'Обращение принято' : item.intakeState === 'DRAFT' ? 'Ожидает отправки' : item.intakeState === 'WITHDRAWN' ? 'Обращение отозвано' : 'Неизвестное состояние'}</strong></li>
                    <li><span>2. Рассмотрение</span><strong>{!item.reviewVisible ? 'Отдельная область доступа' : item.reviewState === null ? 'Запись отсутствует' : item.reviewState === 'PENDING' ? 'Ожидает решения' : item.reviewState === 'NEEDS_INFORMATION' ? 'Требуется ответ' : 'Состояние зафиксировано'}</strong></li>
                    <li><span>3. Исполнение</span><strong>Недоступно</strong></li>
                </ol>
                <aside className="er2-registry-notice"><strong>Что происходит с заявкой</strong><p>{nextStep(item, workspace?.capabilities?.approvalsEnabled)}</p></aside>
                <dl className="er2-summary">
                    <div><dt>Номер заявки</dt><dd><RequestIdCopy key={item.requestId} requestId={item.requestId}/></dd></div>
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
