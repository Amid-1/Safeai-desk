// frontend/src/features/admin/ui/RetentionWorkspace.tsx
import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { getApiErrorMessage } from '../../../shared/api/http'
import ConfirmDialog from '../../../shared/ui/ConfirmDialog'
import { ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import { loadRetention, saveRetention, type RetentionGate, type RetentionStatus } from '../api/retentionApi'
import './RetentionWorkspace.css'

type DeletionScope = 'chat' | 'knowledge'

function isPrepared(gate: RetentionGate): boolean {
    return gate.configured && gate.workerInitialized
}

function readinessReason(gate: RetentionGate): string {
    if (!gate.configured && !gate.workerInitialized) {
        return 'Не завершены настройки и подготовка сервиса'
    }
    if (!gate.configured) return 'Настройки удаления ещё не включены'
    if (!gate.workerInitialized) return 'Сервис удаления ещё не подготовлен'
    return 'Необходимые настройки обнаружены'
}

function StatePill({good, children}: {good: boolean; children: ReactNode}) {
    return <span className={'retention-pill ' + (good ? 'retention-pill--good' : 'retention-pill--muted')}>
        <span className="retention-pill__dot" aria-hidden="true"/>{children}
    </span>
}

function Preparation({label, gate}: {label?: string; gate: RetentionGate}) {
    const prepared = isPrepared(gate)
    return <div className="retention-preparation-row">
        <div>
            {label && <strong>{label}</strong>}
            <span className="retention-preparation-reason">{readinessReason(gate)}</span>
        </div>
        <span className={'retention-preparation-state' + (prepared ? ' retention-preparation-state--ready' : '')}>
            {prepared ? 'Настроено' : 'Не готово'}
        </span>
    </div>
}

function ScopeIcon({scope}: {scope: DeletionScope}) {
    return <span className="retention-card-icon" aria-hidden="true">
        {scope === 'chat' ? <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
            strokeLinecap="round" strokeLinejoin="round">
            <path d="M20 11.5a7.5 7.5 0 0 1-7.5 7.5 8 8 0 0 1-3.2-.7L4 20l1.7-5.2A7.5 7.5 0 1 1 20 11.5Z"/>
            <path d="M8.5 11h7"/>
        </svg> : <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
            strokeLinecap="round" strokeLinejoin="round">
            <path d="M4.5 4.5h6a3 3 0 0 1 3 3V20a3 3 0 0 0-3-3h-6Z"/>
            <path d="M19.5 4.5h-3a3 3 0 0 0-3 3V20a3 3 0 0 1 3-3h3Z"/>
        </svg>}
    </span>
}

export function RetentionWorkspace() {
    const [status, setStatus] = useState<RetentionStatus | null>(null)
    const [refresh, setRefresh] = useState(0)
    const [error, setError] = useState('')
    const [notice, setNotice] = useState('')
    const [change, setChange] = useState<DeletionScope | null>(null)
    const [busy, setBusy] = useState(false)

    useEffect(() => {
        const controller = new AbortController()
        setStatus(null)
        setError('')
        void loadRetention(controller.signal).then(value => {
            if (!controller.signal.aborted) setStatus(value)
        }).catch(cause => {
            if (!controller.signal.aborted) setError(getApiErrorMessage(cause, 'Не удалось загрузить политику.'))
        })
        return () => controller.abort()
    }, [refresh])

    async function apply() {
        if (!status || !change || busy) return
        setBusy(true)
        setError('')
        setNotice('')
        try {
            const result = await saveRetention({
                chatAllowed: change === 'chat' ? !status.chatAllowed : status.chatAllowed,
                knowledgeAllowed: change === 'knowledge' ? !status.knowledgeAllowed : status.knowledgeAllowed,
                expectedVersion: status.version,
            })
            setStatus(result)
            setNotice('Изменения сохранены. Ранее созданные запросы продолжают обрабатываться по своим правилам.')
        } catch (cause) {
            setStatus(null)
            setError(getApiErrorMessage(cause, 'Результат не подтверждён. Обновите состояние политики.'))
        } finally {
            setBusy(false)
            setChange(null)
        }
    }

    const allowedCount = status ? Number(status.chatAllowed) + Number(status.knowledgeAllowed) : 0
    const preparedCount = status ? [status.chat, status.document, status.base].filter(isPrepared).length : 0
    const operationalGap = !!status && (
        (status.chatAllowed && !isPrepared(status.chat))
        || (status.knowledgeAllowed && (!isPrepared(status.document) || !isPrepared(status.base)))
    )
    const toggledAllowed = change === 'chat' ? status?.chatAllowed : status?.knowledgeAllowed
    const changeSubject = change === 'chat' ? 'чатов' : 'документов и баз знаний'

    return <div className="page retention-page">
        <Link className="retention-back" to="/admin/dashboard">← К обзору платформы</Link>
        <header className="retention-hero">
            <div className="retention-hero__copy">
                <span className="retention-eyebrow">Управление данными</span>
                <h1>Хранение и удаление</h1>
                <p>Управляйте новыми запросами на удаление. Действующие ограничения хранения и уже созданные
                    запросы не меняются.</p>
            </div>
            <button className="secondary-button retention-refresh" type="button" disabled={busy}
                onClick={() => { setNotice(''); setRefresh(value => value + 1) }}>
                Обновить состояние
            </button>
        </header>

        {notice && <p className="retention-notice" role="status">{notice}</p>}
        {!status && (error ? <ErrorState title="Политика недоступна" message={error}
            action={<button type="button" onClick={() => setRefresh(value => value + 1)}>Повторить</button>}/>
            : <LoadingState message="Загрузка настроек…"/>)}

        {status && <>
            <section className="retention-summary" aria-label="Политика и техническая готовность">
                <div className="retention-summary__item">
                    <span className="retention-summary__label">Политика платформы</span>
                    <strong>{allowedCount === 2 ? 'Допуск открыт' : allowedCount === 0
                        ? 'Допуск закрыт' : 'Частичный допуск'}</strong>
                    <span className="retention-summary__detail">{allowedCount} из 2 направлений разрешены политикой.
                        Это не подтверждает доступность удаления.</span>
                </div>
                <div className="retention-summary__item">
                    <span className="retention-summary__label">Техническая подготовка</span>
                    <strong>{preparedCount === 3 ? 'Конфигурация готова' : 'Есть ограничения'}</strong>
                    <span className="retention-summary__detail">{preparedCount} из 3 типов настроены.
                        Физическое удаление подтверждается отдельно.</span>
                </div>
            </section>

            {operationalGap && <aside className="retention-alert" role="status">
                <span className="retention-alert__symbol" aria-hidden="true">!</span>
                <div>
                    <strong>Новые операции удаления могут быть отклонены</strong>
                    <p>Разрешение политики не заменяет готовность исполнителя и проверку прав.
                        Неподготовленные операции не принимаются автоматически в очередь.
                        Уже созданные запросы не отменяются изменением политики.</p>
                </div>
            </aside>}

            <section className="retention-panel retention-policy">
                <div className="retention-section-heading">
                    <div>
                        <span className="retention-section-kicker">Правила удаления</span>
                        <h2>Какие новые запросы принимать</h2>
                        <p>Вы можете независимо приостановить удаление чатов или материалов баз знаний.</p>
                    </div>
                    <span className="retention-section-note">Для всех организаций</span>
                </div>

                <div className="retention-grid">
                    <article className="retention-card">
                        <div className="retention-card__header">
                            <ScopeIcon scope="chat"/>
                            <div><h3>Чаты</h3><p>Удаление содержимого архивных чатов</p></div>
                        </div>
                        <div className="retention-card__admission">
                            <span>Приём запросов</span>
                            <StatePill good={status.chatAllowed}>
                                {status.chatAllowed ? 'Разрешён' : 'Приостановлен'}
                            </StatePill>
                        </div>
                        <div className="retention-card__preparation">
                            <h4>Подготовка к удалению</h4>
                            <Preparation gate={status.chat}/>
                        </div>
                        <div className="retention-card__actions">
                            <button type="button" className="secondary-button retention-action"
                                disabled={busy || (!status.chatAllowed && !isPrepared(status.chat))}
                                onClick={() => setChange('chat')}>
                                {status.chatAllowed ? 'Приостановить приём запросов на удаление чатов'
                                    : 'Возобновить приём запросов на удаление чатов'}
                            </button>
                            {!status.chatAllowed && !isPrepared(status.chat) &&
                                <span className="retention-action-hint">Сначала завершите подготовку системы</span>}
                        </div>
                    </article>

                    <article className="retention-card">
                        <div className="retention-card__header">
                            <ScopeIcon scope="knowledge"/>
                            <div><h3>Базы знаний и документы</h3><p>Удаление документов и баз знаний</p></div>
                        </div>
                        <div className="retention-card__admission">
                            <span>Приём запросов</span>
                            <StatePill good={status.knowledgeAllowed}>
                                {status.knowledgeAllowed ? 'Разрешён' : 'Приостановлен'}
                            </StatePill>
                        </div>
                        <div className="retention-card__preparation">
                            <h4>Подготовка к удалению</h4>
                            <Preparation label="Документы" gate={status.document}/>
                            <Preparation label="Базы знаний" gate={status.base}/>
                        </div>
                        <div className="retention-card__actions">
                            <button type="button" className="secondary-button retention-action"
                                disabled={busy || (!status.knowledgeAllowed
                                    && (!isPrepared(status.document) || !isPrepared(status.base)))}
                                onClick={() => setChange('knowledge')}>
                                {status.knowledgeAllowed ? 'Приостановить приём запросов на удаление документов и баз'
                                    : 'Возобновить приём запросов на удаление документов и баз'}
                            </button>
                            {!status.knowledgeAllowed && (!isPrepared(status.document) || !isPrepared(status.base)) &&
                                <span className="retention-action-hint">Сначала завершите подготовку системы</span>}
                        </div>
                    </article>
                </div>
                <p className="retention-policy-footnote">Изменение политики относится только к новым запросам.
                    Уже принятые операции не отменяются. Наличие настроек и обработчика не подтверждает
                    ни его работоспособность, ни фактическое удаление данных.</p>
                <details className="retention-operator-details">
                    <summary>Что нужно подготовить для выполнения удаления</summary>
                    <p>Оператор платформы должен включить разрешённые серверные настройки и подготовить обработчики.
                        Для баз знаний также требуются журнал удаления и защищённое хранилище.</p>
                    <p>Работоспособность подключений и фактическое стирание подтверждаются отдельно.</p>
                </details>
            </section>

            <section className="retention-panel retention-actors" aria-labelledby="retention-actors-heading">
                <div className="retention-section-heading">
                    <div>
                        <span className="retention-section-kicker">Удаление конкретных данных</span>
                        <h2 id="retention-actors-heading">Кто может запросить удаление</h2>
                        <p>Права проверяются для конкретного пользователя, объекта и организации.
                            Эта страница не выдаёт индивидуальных разрешений.</p>
                    </div>
                </div>
                <div className="retention-actor-list">
                    <div><strong>Чат</strong><span>Пользователь архивирует свой чат. Стирание содержимого
                        собственного архивного чата запрашивает администратор при доступной функции.</span>
                        <Link to="/chat">Открыть чаты</Link></div>
                    <div><strong>Документ</strong><span>Владелец базы знаний или администратор
                        своей организации, если сервер разрешает операцию.</span>
                        <Link to="/knowledge">Открыть базы знаний</Link></div>
                    <div><strong>База знаний</strong><span>Администратор своей организации.
                        Принятие зависит от подготовки обработчика и ограничений хранения.</span>
                        <Link to="/knowledge">Открыть базы знаний</Link></div>
                    <div><strong>Пользователь</strong><span>Только суперадминистратор, после отключения,
                        выдержки срока (<strong>{new Intl.NumberFormat('ru-RU', {maximumFractionDigits: 3})
                        .format(status.userRetentionSeconds / 86400)} дн.</strong>) и проверки связанных данных.</span>
                        <Link to="/admin/users">Открыть пользователей</Link></div>
                    <div><strong>Организация</strong><span>Можно отключить с сохранением данных.
                        Необратимое удаление ещё не реализовано.</span>
                        <Link to="/admin/organizations">Открыть организации</Link></div>
                </div>
                <p className="retention-actor-note">Запрос от другого человека, письмо или сообщение
                    не являются автоматическим разрешением на стирание. Для таких обращений потребуется
                    отдельный проверяемый процесс согласования.</p>
            </section>

            <section className="retention-history" aria-labelledby="retention-history-heading">
                <div>
                    <span className="retention-section-kicker">Прозрачность изменений</span>
                    <h2 id="retention-history-heading">История политики</h2>
                    <p>Кто и когда менял правила приёма запросов на удаление.</p>
                </div>
                <Link className="retention-history__link" to="/admin/audit?eventType=RETENTION_POLICY_CHANGED">
                    Открыть историю изменений →
                </Link>
            </section>
        </>}

        {change && status && <ConfirmDialog
            title={toggledAllowed ? `Приостановить приём запросов на удаление ${changeSubject}?`
                : `Возобновить приём запросов на удаление ${changeSubject}?`}
            message={'Настройка изменится для всех организаций. '
                + `Это затронет только новые запросы на удаление ${changeSubject}. `
                + 'Уже созданные операции не отменяются.'}
            confirmText={toggledAllowed ? 'Приостановить новые запросы' : 'Возобновить новые запросы'}
            loading={busy} onConfirm={apply} onCancel={() => setChange(null)}/>}
    </div>
}
