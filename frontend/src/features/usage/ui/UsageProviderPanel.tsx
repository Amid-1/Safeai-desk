// frontend/src/features/usage/ui/UsageProviderPanel.tsx
import { useEffect, useState } from 'react'
import { getUsageProviders, type ProviderRow } from '../api/usageProviderApi'
import type { ExportFilter } from '../api/usageExportApi'
import { useUsageExport } from '../controller/useUsageExport'
import { formatCount, trimDecimal } from '../lib/adminUsagePresentation'

type Props = {filter: ExportFilter; scopeKey: string; blocked: boolean; dirty: boolean}

export function UsageProviderPanel({filter, scopeKey, blocked, dirty}: Props) {
    const [data, setData] = useState<{key: string; rows: ProviderRow[]; error: boolean} | null>(null)
    const [revision, setRevision] = useState(0)
    const task = useUsageExport(scopeKey)
    const {dateFrom, dateTo, organizationId, model, userId} = filter
    useEffect(() => {
        if (blocked) return
        const controller = new AbortController()
        void getUsageProviders({kind: 'providers', dateFrom, dateTo, organizationId, model, userId},
            controller.signal).then(rows => {
            if (!controller.signal.aborted) setData({key: scopeKey, rows, error: false})
        }).catch(() => {
            if (!controller.signal.aborted) setData({key: scopeKey, rows: [], error: true})
        })
        return () => controller.abort()
    }, [blocked, dateFrom, dateTo, organizationId, model, userId, scopeKey, revision])
    const current = data?.key === scopeKey ? data : null
    const rows = current?.rows ?? []
    return <div className="usage-provider-panel">
        <div className="usage-results-toolbar">
            <div className="usage-results-heading">
                <h2>Расходы по провайдерам</h2>
                <p>Фактические физические вызовы, включая повторы. Группировка по историческому провайдеру.</p>
            </div>
            <div className="usage-results-actions">
                <button type="button" className="secondary-button"
                    disabled={blocked || dirty || !rows.length || task.pending}
                    onClick={() => void task.run(filter)}>Подготовить CSV по фильтрам</button>
                {task.ready && <button type="button" className="secondary-button" disabled={dirty || task.pending}
                    onClick={() => void task.download()}>Скачать готовый CSV</button>}
            </div>
        </div>
        {task.pending && <p role="status">Подготовка CSV…</p>}
        {task.error && <p role="alert" className="usage-export-error">{task.error}</p>}
        {blocked ? <p role="status">Снимите фильтр бюджета для просмотра физической аналитики.</p>
            : !current ? <p role="status">Загрузка провайдеров…</p>
            : current.error ? <p role="alert">Отчёт недоступен. <button type="button"
                onClick={() => {setData(null); setRevision(value => value + 1)}}>Повторить</button></p>
            : !rows.length ? <p>Нет физических вызовов за выбранный период.</p>
            : <div className="usage-provider-scroll"><table className="usage-provider-table">
                <thead><tr><th>Провайдер</th><th>Попытки</th><th>В процессе / неоднозначные</th>
                    <th>Входные токены</th><th>Выходные токены</th><th>Известная стоимость</th>
                    <th>Без полной цены / токенов</th></tr></thead>
                <tbody>{rows.map(row => <tr key={row.provider}>
                    <td>{row.provider}</td><td>{formatCount(row.attempts)}</td>
                    <td>{formatCount(row.unresolved)}</td>
                    <td>{row.unknownUsage === row.attempts ? '—' : formatCount(row.input)}</td>
                    <td>{row.unknownUsage === row.attempts ? '—' : formatCount(row.output)}</td>
                    <td>{row.unknownCost === row.attempts ? '—' : `${trimDecimal(row.knownCostUsd)} USD`}</td>
                    <td className={row.unknownCost !== '0' || row.unknownUsage !== '0'
                        ? 'usage-physical-warning' : undefined}>
                        {formatCount(row.unknownCost)} / {formatCount(row.unknownUsage)}</td>
                </tr>)}</tbody>
            </table></div>}
        <p>Период UTC по старту попытки. Модель — исходно запрошенная логическая модель.
            Стоимость расчётная, не счёт провайдера. Не складывайте с итогами сохранённых ответов.</p>
    </div>
}
