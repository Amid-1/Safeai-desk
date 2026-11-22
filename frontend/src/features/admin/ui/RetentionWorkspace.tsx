// frontend/src/features/admin/ui/RetentionWorkspace.tsx
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getApiErrorMessage } from '../../../shared/api/http'
import ConfirmDialog from '../../../shared/ui/ConfirmDialog'
import { ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import { loadRetention, saveRetention, type RetentionGate, type RetentionStatus } from '../api/retentionApi'
import './RetentionWorkspace.css'

function ready(gate: RetentionGate) {
    return gate.configured && gate.workerInitialized
}

function GateStatus({gate, allowed}: {gate: RetentionGate; allowed: boolean}) {
    if (!ready(gate)) return <span className="retention-status retention-status--warning">
        Требуется подготовка сервера
    </span>
    return <span className={'retention-status' + (allowed ? ' retention-status--ready' : '')}>
        {allowed ? 'Разрешено по политике' : 'Приостановлено'}
    </span>
}

export function RetentionWorkspace() {
    const [status, setStatus] = useState<RetentionStatus | null>(null)
    const [refresh, setRefresh] = useState(0)
    const [error, setError] = useState('')
    const [notice, setNotice] = useState('')
    const [change, setChange] = useState<'chat' | 'knowledge' | null>(null)
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
            setNotice('Политика сохранена. Изменение касается только новых запросов на удаление.')
        } catch (cause) {
            setStatus(null)
            setError(getApiErrorMessage(cause, 'Результат не подтверждён. Обновите состояние политики.'))
        } finally {
            setBusy(false)
            setChange(null)
        }
    }

    return <div className="page retention-page">
        <Link to="/admin/dashboard">← Обзор платформы</Link>
        <header className="retention-panel retention-header">
            <div><h1>Хранение и удаление данных</h1>
                <p>Общие правила платформы для новых запросов на необратимое удаление.</p></div>
            <button className="secondary-button" type="button" disabled={busy}
                onClick={() => {setNotice(''); setRefresh(value => value + 1)}}>Обновить состояние</button>
        </header>
        {notice && <p role="status">{notice}</p>}
        {!status && (error ? <ErrorState title="Политика недоступна" message={error}
            action={<button onClick={() => setRefresh(value => value + 1)}>Повторить</button>}/>
            : <LoadingState message="Загрузка политики…"/>)}
        {status && <>
            <section className="retention-panel">
                <h2>Удаление по запросу</h2>
                <p>Настройка не удаляет данные сама по себе. Сервер проверяет права, ограничения хранения
                    и готовность каждого объекта. Уже принятые операции продолжаются.</p>
                <div className="retention-grid">
                    <article className="retention-item">
                        <h3>Чаты</h3>
                        <GateStatus gate={status.chat} allowed={status.chatAllowed}/>
                        <p>Допуск новых запросов: {status.chatAllowed ? 'разрешён' : 'приостановлен'}.</p>
                        <p>Архивирование и восстановление остаются доступны. Администратор может
                            запросить стирание содержимого своего архивного чата.</p>
                        <button type="button" className="secondary-button" disabled={busy
                            || (!status.chatAllowed && !ready(status.chat))}
                            onClick={() => setChange('chat')}>
                            {status.chatAllowed ? 'Приостановить новые удаления' : 'Разрешить новые удаления'}
                        </button>
                    </article>
                    <article className="retention-item">
                        <h3>Базы знаний и документы</h3>
                        <div className="retention-gates">
                            <div>Документы <GateStatus gate={status.document} allowed={status.knowledgeAllowed}/></div>
                            <div>Базы знаний <GateStatus gate={status.base} allowed={status.knowledgeAllowed}/></div>
                        </div>
                        <p>Допуск новых запросов: {status.knowledgeAllowed ? 'разрешён' : 'приостановлен'}.</p>
                        <p>Общая политика, чтобы удаление базы не обходило запрет удаления документов.</p>
                        <button type="button" className="secondary-button" disabled={busy
                            || (!status.knowledgeAllowed && !ready(status.document) && !ready(status.base))}
                            onClick={() => setChange('knowledge')}>
                            {status.knowledgeAllowed ? 'Приостановить новые удаления' : 'Разрешить новые удаления'}
                        </button>
                    </article>
                </div>
                <details><summary>Что требуется от оператора платформы</summary>
                    <p>Отдельные права и подключения исполнителей, утверждённая политика хранения;
                        для баз знаний — неизменяемый журнал удаления и проверенное хранилище.
                        Настройка выполняется при развёртывании, а не при каждом удалении.</p>
                    <p>Статусы отражают конфигурацию и наличие исполнителя на этом сервере,
                        но не подтверждают его текущую работоспособность или завершение операции.</p>
                </details>
            </section>
            <section className="retention-panel">
                <h2>Пользователи и организации</h2>
                <div className="retention-grid">
                    <article className="retention-item"><h3>Учётные записи</h3>
                        <p>Срок после отключения: {new Intl.NumberFormat('ru-RU', {
                            maximumFractionDigits: 3,
                        }).format(status.userRetentionSeconds / 86400)} дн.</p>
                        <p>Окончательное удаление возможно только после проверки связанных данных,
                            отзыва сеансов и подтверждения email.</p>
                        <Link to="/admin/users">Управление пользователями</Link>
                    </article>
                    <article className="retention-item"><h3>Организации</h3>
                        <p>Доступно отключение с сохранением данных. Необратимое удаление не реализовано.</p>
                        <Link to="/admin/organizations">Управление организациями</Link>
                    </article>
                </div>
            </section>
            <section className="retention-panel">
                <h2>История действий</h2>
                <p>Изменения политики и операции удаления фиксируются в журнале событий.</p>
                <Link to="/admin/audit">Открыть журнал событий</Link>
            </section>
            {change && <ConfirmDialog title="Изменить политику удаления?"
                message="Изменение действует на все организации и только на новые запросы. Существующие операции не отменяются."
                confirmText="Сохранить политику" loading={busy} onConfirm={apply}
                onCancel={() => setChange(null)}/>}
        </>}
    </div>
}
