// frontend/src/features/admin/ui/RetentionWorkspace.tsx
import { useEffect, useState, type ReactNode } from 'react'
import DataManagementIcon from '../../../shared/ui/DataManagementIcon'
import { WorkspaceLink } from '../../../shared/ui/WorkspaceReturn'
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
        <header className="retention-compact-heading"><div><h1>Политика хранения</h1></div>
            <button className="secondary-button retention-refresh" type="button" disabled={busy}
                onClick={() => { setNotice(''); setRefresh(value => value + 1) }}>Обновить состояние</button></header>

        {notice && <p className="retention-notice" role="status">{notice}</p>}
        {!status && (error ? <ErrorState title="Политика недоступна" message={error}
            action={<button type="button" onClick={() => setRefresh(value => value + 1)}>Повторить</button>}/>
            : <LoadingState message="Загрузка настроек…"/>)}

        {status && <>
            <div className="retention-compact-summary" role="status">
                <strong>{allowedCount} из 2 направлений разрешены для отдельных операций.</strong>{' '}{preparedCount} из 3 типов настроены.
                {operationalGap && <p><strong>Новые операции удаления могут быть отклонены.</strong>{' '}Неподготовленные операции не принимаются автоматически в очередь.</p>}
            </div>
            <section className="retention-panel retention-policy">
                <div className="retention-policy-table-wrap"><table className="retention-policy-table">
                    <thead><tr><th scope="col">Данные</th><th scope="col">Допуск операций</th><th scope="col">Готовность</th><th scope="col">Действие</th></tr></thead>
                    <tbody>
                        <tr><th scope="row"><span className="retention-data-name"><DataManagementIcon name="message"/>Чаты</span></th>
                            <td><StatePill good={status.chatAllowed}>{status.chatAllowed ? 'Разрешён' : 'Приостановлен'}</StatePill></td>
                            <td><Preparation gate={status.chat}/></td>
                            <td><button type="button" className="secondary-button retention-action" disabled={busy || (!status.chatAllowed && !isPrepared(status.chat))}
                                aria-label={status.chatAllowed ? 'Приостановить приём запросов на удаление чатов' : 'Возобновить приём запросов на удаление чатов'}
                                onClick={() => setChange('chat')}>{status.chatAllowed ? 'Приостановить' : 'Возобновить'}</button>
                                {!status.chatAllowed && !isPrepared(status.chat) && <small className="retention-action-hint">Сначала завершите подготовку системы</small>}</td></tr>
                        <tr><th scope="row"><span className="retention-data-name"><DataManagementIcon name="database"/>Базы знаний и документы</span></th>
                            <td><StatePill good={status.knowledgeAllowed}>{status.knowledgeAllowed ? 'Разрешён' : 'Приостановлен'}</StatePill></td>
                            <td><Preparation label="Документы" gate={status.document}/><Preparation label="Базы знаний" gate={status.base}/></td>
                            <td><button type="button" className="secondary-button retention-action" disabled={busy || (!status.knowledgeAllowed && (!isPrepared(status.document) || !isPrepared(status.base)))}
                                aria-label={status.knowledgeAllowed ? 'Приостановить приём запросов на удаление документов и баз' : 'Возобновить приём запросов на удаление документов и баз'}
                                onClick={() => setChange('knowledge')}>{status.knowledgeAllowed ? 'Приостановить' : 'Возобновить'}</button>
                                {!status.knowledgeAllowed && (!isPrepared(status.document) || !isPrepared(status.base)) && <small className="retention-action-hint">Сначала завершите подготовку системы</small>}</td></tr>
                    </tbody>
                </table></div>
                <p className="retention-policy-footnote">Приём личных обращений управляется отдельно. Изменение политики относится только к новым отдельным операциям удаления.
                    Уже принятые операции не отменяются. Наличие настроек и обработчика не подтверждает
                    ни его работоспособность, ни фактическое удаление данных.</p>
                <details className="retention-operator-details">
                    <summary>Что нужно подготовить для выполнения удаления</summary>
                    <p>Оператор платформы должен включить разрешённые серверные настройки и подготовить обработчики.
                        Для баз знаний также требуются журнал удаления и защищённое хранилище.</p>
                    <p>Работоспособность подключений и фактическое стирание подтверждаются отдельно.</p>
                </details>
            </section>

            <details className="retention-panel retention-actors"><summary>Кто может выполнять отдельные операции удаления</summary>
                <p>Ниже перечислены действующие отдельные операции, а не права подачи личных обращений. Права проверяются для конкретного объекта и организации.</p>
                <div className="retention-actor-list">
                    <div><strong>Чат</strong><span>Пользователь архивирует свой чат. Стирание содержимого
                        собственного архивного чата запрашивает администратор при доступной функции.</span>
                        </div>
                    <div><strong>Документ</strong><span>Владелец базы знаний или администратор
                        своей организации, если сервер разрешает операцию.</span>
                        </div>
                    <div><strong>База знаний</strong><span>Администратор своей организации.
                        Принятие зависит от подготовки обработчика и ограничений хранения.</span>
                        </div>
                    <div><strong>Пользователь</strong><span>Только суперадминистратор, после отключения,
                        выдержки срока (<strong>{new Intl.NumberFormat('ru-RU', {maximumFractionDigits: 3})
                        .format(status.userRetentionSeconds / 86400)} дн.</strong>) и проверки связанных данных.</span>
                        </div>
                    <div><strong>Организация</strong><span>Можно отключить с сохранением данных.
                        Необратимое удаление ещё не реализовано.</span>
                        </div>
                </div>
                <p className="retention-actor-note">Запрос от другого человека, письмо или сообщение
                    не являются автоматическим разрешением на стирание. Для таких обращений потребуется
                    отдельный проверяемый процесс согласования.</p>
            </details>

            <section className="retention-panel retention-quick-links" aria-label="Связанные разделы">
                <WorkspaceLink className="retention-quick-link" to="/admin/audit?eventType=RETENTION_POLICY_CHANGED">
                    <DataManagementIcon name="history"/><span><strong>История политики хранения</strong><small>Кто и когда менял правила приёма запросов на удаление.</small></span><DataManagementIcon name="chevron"/>
                </WorkspaceLink>
                <WorkspaceLink className="retention-quick-link" to="/admin/users"><DataManagementIcon name="users"/><span>Открыть пользователей</span><DataManagementIcon name="chevron"/></WorkspaceLink>
                <WorkspaceLink className="retention-quick-link" to="/admin/organizations"><DataManagementIcon name="building"/><span>Открыть организации</span><DataManagementIcon name="chevron"/></WorkspaceLink>
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
