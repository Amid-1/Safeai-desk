// frontend/src/pages/ErasureReviewDetailsPage.tsx
import { WorkspaceReturn } from '../shared/ui/WorkspaceReturn'
// frontend/src/pages/ErasureReviewDetailsPage.tsx
import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
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
const REASONS: Record<string, string> = {
    VERIFIED_OWNER_REQUEST: 'Запрос владельца подтверждён',
    VALID_TENANT_REQUEST: 'Запрос организации признан обоснованным',
    INSUFFICIENT_AUTHORITY: 'Недостаточно полномочий',
    INSUFFICIENT_INFORMATION: 'Недостаточно сведений',
    RETENTION_REVIEW_REQUIRED: 'Требуется проверка сроков хранения',
    SECURITY_REVIEW_REQUIRED: 'Требуется проверка безопасности',
}
const DECISION_REASONS: Record<Decision, readonly [string, ...string[]]> = {
    NEEDS_INFORMATION: ['INSUFFICIENT_INFORMATION', 'RETENTION_REVIEW_REQUIRED', 'SECURITY_REVIEW_REQUIRED'],
    APPROVED_REVIEW_ONLY: ['VERIFIED_OWNER_REQUEST', 'VALID_TENANT_REQUEST'],
    REJECTED: ['INSUFFICIENT_AUTHORITY', 'INSUFFICIENT_INFORMATION',
        'RETENTION_REVIEW_REQUIRED', 'SECURITY_REVIEW_REQUIRED'],
}
const DECISIONS: { code: Decision; label: string; hint: string }[] = [
    { code: 'APPROVED_REVIEW_ONLY', label: 'Предварительно согласовать', hint: 'Не разрешает исполнение' },
    { code: 'NEEDS_INFORMATION', label: 'Запросить уточнение', hint: 'Вернуть заявителю' },
    { code: 'REJECTED', label: 'Отказать', hint: 'С указанием основания' },
]
const HOLD_REASONS: Record<string, string> = {
    LEGAL_REQUEST: 'Юридическое требование',
    MANDATORY_RETENTION: 'Обязательный срок хранения',
    SECURITY_REVIEW: 'Проверка безопасности',
}
const FINAL_STATES = new Set(['APPROVED_REVIEW_ONLY', 'REJECTED', 'WITHDRAWN'])

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
    const [reason, setReason] = useState('INSUFFICIENT_INFORMATION')
    const [holdReason, setHoldReason] = useState('LEGAL_REQUEST')
    const [reloadKey, setReloadKey] = useState(0)

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
        setError(null)
        void reload(controller.signal).catch(err => {
            if (!controller.signal.aborted) {
                setCaseData(null); setHistory([]); setAccess({ reviewer: false, legal: false })
                setError(getApiErrorMessage(err, 'Не удалось получить заявку.'))
            }
        }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
        return () => controller.abort()
    }, [reload, reloadKey])

    async function action(task: (item: ReviewView) => Promise<ReviewView>) {
        if (!caseData || busy || loading || error) return
        setBusy(true)
        setError(null)
        setNotice(null)
        try {
            const next = await task(caseData)
            setCaseData(next)
            setNotice('Действие зарегистрировано. Физическое удаление не начиналось.')
            try { await reload() } catch {
                setCaseData(null); setHistory([]); setAccess({ reviewer: false, legal: false })
                setError('Данные и полномочия не подтверждены. Обновите карточку.')
                setNotice('Действие зарегистрировано, но история пока не обновилась. Обновите карточку.')
            }
        } catch (err) {
            setError(getApiErrorMessage(err, 'Действие не выполнено. Обновите карточку.'))
        } finally { setBusy(false) }
    }

    const back = reviewer ? '/admin/erasure/requests' : '/my/erasure-requests'
    if (!requestId) return <ErrorState message="Неверный идентификатор заявки." />
    const mayReview = !error && !!caseData && reviewer && access.reviewer && !caseData.holdActive
        && caseData.baseState === 'SUBMITTED' && caseData.reviewState === 'PENDING'
        && caseData.requesterUserId !== currentUser?.id
    const mayHold = !error && !!caseData && reviewer && access.legal && caseData.baseState === 'SUBMITTED'
        && caseData.reviewState === 'PENDING' && caseData.requesterUserId !== currentUser?.id
    const mayClarify = !error && !!caseData && !reviewer && caseData.baseState === 'SUBMITTED'
        && caseData.reviewState === 'NEEDS_INFORMATION'
    const isFinal = caseData ? FINAL_STATES.has(caseData.reviewState) : false

    return <section className="er2-page page er2-case-page">
        <WorkspaceReturn fallback={back} />
        <header className="er2-heading er2-heading--row">
            <div>
                <h1>Рассмотрение заявки</h1>
                <p>История, основания решения и юридические ограничения в одном месте.</p></div>
            <button type="button" className="er2-action" disabled={loading || busy}
                onClick={() => setReloadKey(key => key + 1)}>Обновить карточку</button>
        </header>
        {loading ? <LoadingState message="Загружаем заявку..." /> : !caseData ? <div className="er2-card">
            <ErrorState message={error ?? 'Заявка недоступна.'} />
            <button type="button" className="er2-action" onClick={() => setReloadKey(key => key + 1)}>Повторить</button>
        </div> : <>
            <div className="er2-case-topline">
                <div><span className="er2-eyebrow">ОБЪЕКТ ЗАЯВКИ</span>
                    <h2>{RESOURCE_NAMES[caseData.resourceType] ?? caseData.resourceType}</h2>
                    <span className="er2-muted">Создана {reviewTime(caseData.createdAt)}</span></div>
                <span className={`er2-status er2-status--${caseData.reviewState.toLowerCase()}`}>
                    {REVIEW_STATES[caseData.reviewState]}</span>
            </div>
            <ol className="er2-progress" aria-label="Этапы обработки заявки">
                <li className="er2-progress--done"><span>1</span><div><strong>Регистрация</strong><small>Заявка создана</small></div></li>
                <li className={caseData.reviewState === 'PENDING' || caseData.reviewState === 'NEEDS_INFORMATION'
                    ? 'er2-progress--active' : 'er2-progress--done'}>
                    <span>2</span><div><strong>Рассмотрение</strong><small>{caseData.reviewState === 'NEEDS_INFORMATION'
                        ? 'Нужно уточнение' : isFinal ? 'Этап завершён' : 'В работе'}</small></div></li>
                <li className={isFinal ? 'er2-progress--active' : ''}><span>3</span>
                    <div><strong>Результат</strong><small>{isFinal ? REVIEW_STATES[caseData.reviewState] : 'Ожидается'}</small></div></li>
                <li><span>4</span><div><strong>Исполнение</strong><small>Не подключено</small></div></li>
            </ol>
            <div className="er2-details-grid">
                <div className="er2-details-main">
                    <div className="er2-card"><div className="er2-panel-heading"><h2>Сведения о заявке</h2>
                        </div>
                        <dl className="er2-details">
                            <div><dt>Объект</dt><dd>{RESOURCE_NAMES[caseData.resourceType] ?? caseData.resourceType}</dd></div>

                            <div><dt>Состояние заявки</dt><dd>{caseData.baseState === 'SUBMITTED' ? 'Зарегистрирована'
                                : caseData.baseState === 'WITHDRAWN' ? 'Отозвана' : caseData.baseState}</dd></div>
                            <div><dt>Последнее изменение</dt><dd>{reviewTime(caseData.updatedAt)}</dd></div>
                            <div><dt>Основание решения</dt><dd>{caseData.reasonCode
                                ? (REASONS[caseData.reasonCode] ?? HOLD_REASONS[caseData.reasonCode]
                                    ?? 'Основание зарегистрировано') : 'Ещё не указано'}</dd></div>
                        </dl>
                        <details className="er2-technical"><summary>Технические идентификаторы</summary>
                            <p>Заявка: {caseData.requestId}</p><p>Объект: {caseData.resourceId}</p><p>Версия: {caseData.version}</p><p>Область: {caseData.scope}</p>
                        </details>
                    </div>
                    <div className="er2-banner er2-banner--warning" role="note">
                        <span className="er2-banner-mark" aria-hidden="true">!</span><div>
                            <strong>Даже согласованная заявка не разрешает физическое удаление.</strong>
                            <p>Проверка личности, последствий, удержаний и готовности исполнителя проводятся отдельно.</p>
                        </div>
                    </div>
                    {mayReview && <div className="er2-card er2-decision-card">
                        <div className="er2-eyebrow">НЕЗАВИСИМОЕ РЕШЕНИЕ</div><h2>Решение проверяющего</h2>
                        <p className="er2-muted">Выберите одно действие. Доступные основания зависят от решения,
                            сервер повторно проверяет права и версию заявки.</p>
                        <fieldset className="er2-choice-grid" disabled={busy}><legend>Какое решение принять?</legend>
                            {DECISIONS.map(option => <label key={option.code}
                                className={decision === option.code ? 'er2-choice er2-choice--selected' : 'er2-choice'}>
                                <input type="radio" name="erasure-decision" value={option.code}
                                    checked={decision === option.code} onChange={() => {
                                        setDecision(option.code)
                                        setReason(DECISION_REASONS[option.code][0])
                                    }} />
                                <span><strong>{option.label}</strong><small>{option.hint}</small></span>
                            </label>)}
                        </fieldset>
                        <label className="er2-label">Основание решения
                            <select value={reason} disabled={busy}
                                onChange={e => setReason(e.target.value)}>
                                {DECISION_REASONS[decision].map(code => <option key={code} value={code}>
                                    {REASONS[code]}</option>)}
                            </select>
                        </label>
                        <div className="er2-decision-footer"><span>Решение фиксируется в журнале.</span>
                            <button className="er2-primary" type="button" disabled={busy}
                                onClick={() => { if (window.confirm('Зафиксировать решение? Удаление не начнётся.')) {
                                    void action(item => reviewDecision(item, decision, reason))
                                } }}>Сохранить решение</button></div>
                    </div>}
                    {mayHold && <div className="er2-card">
                        <div className="er2-eyebrow">ОГРАНИЧЕНИЯ</div><h2>Юридическое удержание</h2>
                        <p className="er2-muted">Удержание препятствует согласованию. Снять его может только другой
                            уполномоченный сотрудник, а не тот, кто установил.</p>
                        {!caseData.holdActive && <label className="er2-label">Основание удержания
                            <select value={holdReason} disabled={busy}
                                onChange={e => setHoldReason(e.target.value)}>
                                {Object.entries(HOLD_REASONS).map(([code, name]) =>
                                    <option key={code} value={code}>{name}</option>)}
                            </select></label>}
                        <button className="er2-action" type="button" disabled={busy}
                            onClick={() => { if (window.confirm(caseData.holdActive
                                ? 'Запросить снятие удержания? Это разрешено только другому уполномоченному сотруднику.'
                                : 'Установить юридическое удержание?')) {
                                void action(item => caseData.holdActive ? releaseLegalHold(item)
                                    : applyLegalHold(item, holdReason))
                            } }}>{caseData.holdActive ? 'Снять удержание' : 'Установить удержание'}</button>
                    </div>}
                    {mayClarify && <div className="er2-card">
                        <h2>Нужно уточнение</h2>
                        <p>Загрузка дополнительных сведений ещё не подключена. Можно сообщить о готовности
                            вернуться к рассмотрению без передачи документов.</p>
                        <button className="er2-action" disabled={busy} type="button"
                            onClick={() => void action(provideReviewInformation)}>
                            Вернуть на рассмотрение</button>
                    </div>}
                    {reviewer && !mayReview && !mayHold && <div className="er2-card">
                        <h2>Действия ограничены</h2>
                        <p>Решения доступны только назначенным проверяющим, при отсутствии удержания,
                            если заявка ожидает рассмотрения и проверяющий не является заявителем.</p>
                    </div>}
                </div>
                <aside className="er2-details-side">
                    <div className="er2-card"><div className="er2-eyebrow">СВОДКА</div><h2>Текущий статус</h2>
                        <dl className="er2-summary">
                            <div><dt>Рассмотрение</dt><dd>{REVIEW_STATES[caseData.reviewState]}</dd></div>
                            <div><dt>Удержание</dt><dd>{caseData.holdActive ? 'Активно' : 'Нет'}</dd></div>
                            <div><dt>Исполнение</dt><dd>Отключено</dd></div>
                        </dl></div>
                    <div className="er2-card"><div className="er2-eyebrow">ХРОНОЛОГИЯ</div><h2>История решений</h2>
                        {history.length === 0 ? <p className="er2-muted">События пока недоступны.</p> :
                            <ol className="er2-timeline">{history.map(item => <li key={`${item.version}-${item.eventCode}`}>
                                <strong>{EVENT_LABELS[item.eventCode] ?? 'Событие рассмотрения'}</strong>
                                {item.reasonCode && <span>{REASONS[item.reasonCode]
                                    ?? HOLD_REASONS[item.reasonCode] ?? 'Причина зарегистрирована'}</span>}
                                <small>{reviewTime(item.createdAt)}</small>
                            </li>)}</ol>}
                    </div>

                </aside>
            </div>
        </>}
        {error && caseData && <div role="alert" className="er2-error">{error}
            <button type="button" className="er2-action" disabled={busy}
                onClick={() => setReloadKey(key => key + 1)}>Обновить данные</button></div>}
        {notice && <div role="status" className="er2-notice">{notice}</div>}
    </section>
}
