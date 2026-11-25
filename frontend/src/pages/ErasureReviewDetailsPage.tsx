// frontend/src/pages/ErasureReviewDetailsPage.tsx
import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useAuth } from '../features/auth/state/useAuth'
import { getApiErrorMessage } from '../shared/api/http'
import {
    applyLegalHold, loadMyReview, loadReview, loadReviewAccess,
    loadReviewHistory, provideReviewInformation, releaseLegalHold,
    reviewDecision, type Decision, type ReviewAccess,
    type ReviewEvent, type ReviewView,
} from '../features/erasure/review/reviewApi'
import { EVENT_LABELS, RESOURCE_NAMES, REVIEW_STATES, reviewTime } from
    '../features/erasure/review/reviewLabels'
import { ErrorState, LoadingState } from '../shared/ui/StateBlock'
import '../features/erasure/review/ErasureReview.css'

type Props = { reviewer?: boolean }
const REASONS = {
    VERIFIED_OWNER_REQUEST: 'Проверенный запрос владельца',
    VALID_TENANT_REQUEST: 'Запрос организации признан обоснованным',
    INSUFFICIENT_AUTHORITY: 'Недостаточно полномочий',
    INSUFFICIENT_INFORMATION: 'Недостаточно сведений',
    RETENTION_REVIEW_REQUIRED: 'Требуется проверка сроков хранения',
    SECURITY_REVIEW_REQUIRED: 'Требуется проверка безопасности',
}
const HOLD_REASONS = {
    LEGAL_REQUEST: 'Юридическое требование',
    MANDATORY_RETENTION: 'Обязательный срок хранения',
    SECURITY_REVIEW: 'Проверка безопасности',
}
export default function ErasureReviewDetailsPage({ reviewer = false }: Props) {
    const { requestId } = useParams<{ requestId: string }>()
    const { currentUser } = useAuth()
    const [caseData, setCaseData] = useState<ReviewView | null>(null)
    const [history, setHistory] = useState<ReviewEvent[]>([])
    const [access, setAccess] = useState<ReviewAccess>({ reviewer: false, legal: false })
    const [loading, setLoading] = useState(true)
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [notice, setNotice] = useState<string | null>(null)
    const [decision, setDecision] = useState<Decision>('NEEDS_INFORMATION')
    const [reason, setReason] = useState<keyof typeof REASONS>('INSUFFICIENT_INFORMATION')
    const [holdReason, setHoldReason] = useState<keyof typeof HOLD_REASONS>('LEGAL_REQUEST')

    const reload = useCallback(async (signal?: AbortSignal) => {
        if (!requestId) return
        const [entry, events, permissions] = await Promise.all([
            reviewer ? loadReview(requestId, signal) : loadMyReview(requestId, signal),
            loadReviewHistory(requestId, reviewer, signal),
            reviewer ? loadReviewAccess(signal) : Promise.resolve({ reviewer: false, legal: false }),
        ])
        if (!signal?.aborted) { setCaseData(entry); setHistory(events); setAccess(permissions) }
    }, [requestId, reviewer])

    useEffect(() => {
        const controller = new AbortController()
        setLoading(true)
        void reload(controller.signal).catch(err => {
            if (!controller.signal.aborted) setError(getApiErrorMessage(err, 'Не удалось получить заявку.'))
        }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
        return () => controller.abort()
    }, [reload])

    async function action(task: (item: ReviewView) => Promise<ReviewView>) {
        if (!caseData || busy) return
        setBusy(true); setError(null); setNotice(null)
        try {
            await task(caseData)
            await reload()
            setNotice('Действие зарегистрировано. Физическое удаление не начиналось.')
        } catch (err) {
            setError(getApiErrorMessage(err, 'Действие не выполнено. Обновите карточку.'))
        } finally { setBusy(false) }
    }

    const back = reviewer ? '/admin/erasure/requests' : '/my/erasure-requests'
    if (!requestId) return <ErrorState message="Неверный идентификатор заявки." />
    return <section className="er2-page page">
        <Link to={back} className="er2-back">← Вернуться к заявкам</Link>
        <header className="er2-heading">
            <div className="er2-eyebrow">УПРАВЛЕНИЕ ДАННЫМИ / ЗАЯВКА</div>
            <h1>Карточка рассмотрения</h1>
            <p>Решения и удержания не запускают удаление. История доступна только уполномоченным лицам.</p>
        </header>
        {loading ? <LoadingState message="Загружаем заявку..." /> : !caseData ?
            <ErrorState message={error ?? 'Заявка недоступна.'}/> : <div className="er2-details-grid">
            <div className="er2-details-main">
                <div className="er2-card">
                    <div className="er2-panel-heading"><h2>Предмет запроса</h2>
                        <span className="er2-pill">{REVIEW_STATES[caseData.reviewState]}</span></div>
                    <dl className="er2-details">
                        <div><dt>Объект</dt><dd>{RESOURCE_NAMES[caseData.resourceType] ?? caseData.resourceType}</dd></div>
                        <div><dt>Область</dt><dd>{caseData.scope}</dd></div>
                        <div><dt>Зарегистрирована</dt><dd>{reviewTime(caseData.createdAt)}</dd></div>
                        <div><dt>Состояние исходной заявки</dt><dd>{caseData.baseState}</dd></div>
                        <div><dt>Удержание</dt><dd>{caseData.holdActive ? 'Активно: рассмотрение ограничено' : 'Нет'}</dd></div>
                        <div><dt>Версия рассмотрения</dt><dd>{caseData.version}</dd></div>
                    </dl>
                    <details className="er2-technical"><summary>Технические идентификаторы</summary>
                        <p>Запрос: {caseData.requestId}</p><p>Объект: {caseData.resourceId}</p>
                    </details>
                </div>
                <div className="er2-card er2-warning" role="note">
                    <strong>Исполнение не разрешено.</strong> Даже после предварительного согласования
                    потребуется отдельная проверка личности, зависимостей, удержаний и исполнителя.
                </div>
                {reviewer && access.reviewer && !caseData.holdActive
                    && caseData.baseState === 'SUBMITTED'
                    && caseData.reviewState === 'PENDING'
                    && caseData.requesterUserId !== currentUser?.id && <div className="er2-card">
                    <h2>Решение проверяющего</h2>
                    <p className="er2-muted">Укажите результат и стандартизированное основание.
                        Это только решение по рассмотрению заявки.</p>
                    <fieldset className="er2-decision-options" disabled={busy}>
                        <legend>Решение</legend>
                        {([['NEEDS_INFORMATION', 'Запросить уточнение'],
                            ['APPROVED_REVIEW_ONLY', 'Предварительно согласовать'],
                            ['REJECTED', 'Отказать']] as const).map(([code, name]) =>
                            <label key={code}><input type="radio" name="decision" checked={decision === code}
                                onChange={() => setDecision(code)}/>{name}</label>)}
                    </fieldset>
                    <label className="er2-label">Основание
                        <select value={reason} onChange={e => setReason(e.target.value as keyof typeof REASONS)}>
                            {Object.entries(REASONS).map(([key, name]) => <option key={key} value={key}>{name}</option>)}
                        </select>
                    </label>
                    <button className="er2-primary" type="button" disabled={busy}
                        onClick={() => { if (window.confirm('Зафиксировать решение? Удаление не начнётся.')) {
                            void action(item => reviewDecision(item, decision, reason))
                        } }}>Сохранить решение</button>
                </div>}
                {reviewer && access.legal && caseData.baseState === 'SUBMITTED'
                    && caseData.requesterUserId !== currentUser?.id
                    && caseData.reviewState !== 'WITHDRAWN'
                    && caseData.reviewState !== 'REJECTED' && <div className="er2-card">
                    <h2>Юридическое удержание</h2>
                    {!caseData.holdActive && <label className="er2-label">Основание
                        <select value={holdReason}
                            onChange={e => setHoldReason(e.target.value as keyof typeof HOLD_REASONS)}>
                            {Object.entries(HOLD_REASONS).map(([key, name]) =>
                                <option key={key} value={key}>{name}</option>)}
                        </select></label>}
                    <button className="er2-action" type="button" disabled={busy}
                        onClick={() => { if (window.confirm(caseData.holdActive
                            ? 'Подтвердить ходатайство о снятии удержания?'
                            : 'Установить юридическое удержание?')) {
                            void action(item => caseData.holdActive ? releaseLegalHold(item)
                                : applyLegalHold(item, holdReason))
                        } }}>
                        {caseData.holdActive ? 'Снять удержание (другой сотрудник)' : 'Установить удержание'}
                    </button>
                </div>}
                {!reviewer && caseData.reviewState === 'NEEDS_INFORMATION' &&
                    caseData.baseState === 'SUBMITTED' && <div className="er2-card">
                    <h2>Нужно уточнение</h2>
                    <p>Канал передачи дополнительных сведений пока не подключён.
                        Здесь можно лишь подтвердить готовность к повторному рассмотрению.</p>
                    <button className="er2-action" disabled={busy} type="button"
                        onClick={() => void action(provideReviewInformation)}>
                        Вернуть на рассмотрение без передачи файлов
                    </button>
                </div>}
            </div>
            <aside className="er2-details-side">
                <div className="er2-card"><h2>Сводка</h2>
                    <dl className="er2-summary">
                        <div><dt>Состояние</dt><dd>{REVIEW_STATES[caseData.reviewState]}</dd></div>
                        <div><dt>Удержание</dt><dd>{caseData.holdActive ? 'Да' : 'Нет'}</dd></div>
                        <div><dt>Удаление</dt><dd>Выключено</dd></div>
                    </dl></div>
                <div className="er2-card"><h2>История рассмотрения</h2>
                    <ol className="er2-timeline">{history.map(item => <li key={item.version}>
                        <strong>{EVENT_LABELS[item.eventCode] ?? item.eventCode}</strong>
                        <small>{reviewTime(item.createdAt)}</small>
                    </li>)}</ol>
                </div>
            </aside>
        </div>}
        {error && caseData && <div role="alert" className="er2-error">{error}</div>}
        {notice && <div role="status" className="er2-notice">{notice}</div>}
    </section>
}
