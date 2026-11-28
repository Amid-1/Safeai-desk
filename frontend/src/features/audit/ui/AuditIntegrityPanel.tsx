// frontend/src/features/audit/ui/AuditIntegrityPanel.tsx
import { useEffect, useState } from 'react'
import { getAuditIntegrity, type IntegrityState, type IntegrityStatus } from '../api/auditIntegrityApi'

const LABELS: Record<IntegrityState, string> = {
    NOT_CONFIGURED: 'Независимая проверка не настроена', VERIFIED: 'Целостность подтверждена',
    VIOLATION: 'Обнаружены нарушения целостности', UNAVAILABLE: 'Проверка недоступна',
    STALE: 'Последняя проверка устарела', INVALID: 'Отчёт не прошёл проверку подписи или формата',
    PENDING: 'Есть события без внешнего подтверждения',
}

export default function AuditIntegrityPanel({ compact = false }: { compact?: boolean }) {
    const [report, setReport] = useState<IntegrityStatus | null>(null)
    const [failed, setFailed] = useState(false)
    const [refresh, setRefresh] = useState(0)
    useEffect(() => {
        const controller = new AbortController()
        let timer: ReturnType<typeof setTimeout> | undefined
        let expiry: ReturnType<typeof setTimeout> | undefined
        async function load() {
            try {
                const result = await getAuditIntegrity(controller.signal)
                if (!controller.signal.aborted) {
                    clearTimeout(expiry)
                    let next = result
                    if (result.freshUntil) {
                        const remaining = Date.parse(result.freshUntil) - Date.now()
                        if (remaining <= 0) next = { ...result, state: 'STALE' }
                        else expiry = setTimeout(() => setReport(old => old ? { ...old, state: 'STALE' } : null), Math.min(remaining, 900_000))
                    }
                    setReport(next); setFailed(false)
                }
            } catch {
                if (!controller.signal.aborted) { setReport(null); setFailed(true) }
            } finally {
                if (!controller.signal.aborted) timer = setTimeout(() => { void load() }, 30_000)
            }
        }
        void load()
        return () => { controller.abort(); clearTimeout(timer); clearTimeout(expiry) }
    }, [refresh])

    const state = report?.state
    const verified = state === 'VERIFIED'
    const available = state === 'VERIFIED' || state === 'VIOLATION' || state === 'PENDING'
    const tone = verified ? 'success' : state === 'VIOLATION' || state === 'INVALID' ? 'danger' : 'warning'
    const label = state ? LABELS[state] : failed ? 'Не удалось получить состояние проверки' : 'Получение состояния проверки…'
    const lastCheck = report?.checkedAt ? new Date(report.checkedAt).toLocaleString('ru-RU') : '—'
    return <section className="audit-investigation audit-integrity" aria-label="Криптографическая целостность журнала">
        <div className="audit-investigation__heading">
            <h2>Криптографическая целостность журнала</h2>
            <button type="button" className="secondary-button" onClick={() => {
                setReport(null); setFailed(false); setRefresh(value => value + 1)
            }}>Обновить состояние</button>
        </div>
        <p className={'audit-integrity__status audit-integrity__status--' + tone} role="status">{label}</p>
        <details className="audit-integrity__details">
            <summary>{compact ? 'Параметры проверки' : 'Показать параметры независимой проверки'}</summary>
            <dl className="audit-integrity__grid">
                <div><dt>Проверено закреплённых событий</dt><dd>{available ? report?.checkedEvents : '—'}</dd></div>
                <div><dt>Нарушений целостности</dt><dd>{available ? report?.violations : '—'}</dd></div>
                <div><dt>Алгоритм подписи</dt><dd>Ed25519</dd></div>
                <div><dt>Внешнее хранение</dt><dd>{available ? 'WORM COMPLIANCE' : 'Не подтверждено'}</dd></div>
                <div><dt>Последняя проверка</dt><dd>{lastCheck}</dd></div>
                <div><dt>Состояние архива</dt><dd>{available ? 'Доступен на момент проверки' : 'Не подтверждено'}</dd></div>
                <div><dt>Новых незакреплённых событий</dt><dd>{available ? report?.unanchoredEvents : '—'}</dd></div>
            </dl>
            <p className="muted audit-investigation__hint">Независимая проверка всего журнала,
                не зависит от фильтров. Действительность отчёта — 15 минут.</p>
        </details>
        {state === 'VIOLATION' && <p>Сохраните доказательства и обратитесь к ответственному за безопасность. Не удаляйте записи и не создавайте новый контрольный снимок до расследования.</p>}
    </section>
}
