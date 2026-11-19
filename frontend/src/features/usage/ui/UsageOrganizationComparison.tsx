// frontend/src/features/usage/ui/UsageOrganizationComparison.tsx
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getUsageByOrganizations } from '../api/usage/usageClient'
import type { UsageOrganizationSummary } from '../api/usage/usageTypes'
import { toUsageFilter } from '../lib/adminUsageFilterState'
import { formatCount, trimDecimal } from '../lib/adminUsagePresentation'
import { downloadUsageCsv } from './usageCsv'

type Props = { dateFrom: string; dateTo: string; model: string; userId: string }

export function UsageOrganizationComparison({ dateFrom, dateTo, model, userId }: Props) {
    const [page, setPage] = useState(0)
    const [retry, setRetry] = useState(0)
    const [state, setState] = useState<{page: number; retry: number; rows: UsageOrganizationSummary[]; next: boolean; error: boolean} | null>(null)
    const [exportError, setExportError] = useState(false)
    useEffect(() => {
        const controller = new AbortController()
        async function load() {
            try {
                const { filter } = toUsageFilter({ dateFrom, dateTo, model, userId, organizationId: '' }, true)
                const result = await getUsageByOrganizations(page, 50, filter, { signal: controller.signal })
                if (result.page !== page || result.size !== 50) throw new Error('Invalid page')
                if (!controller.signal.aborted) setState({ page, retry, rows: result.content, next: page + 1 < result.totalPages, error: false })
            } catch {
                if (!controller.signal.aborted) setState({ page, retry, rows: [], next: false, error: true })
            }
        }
        void load()
        return () => controller.abort()
    }, [dateFrom, dateTo, model, userId, page, retry])
    const current = state?.page === page && state.retry === retry ? state : null
    const rows = current?.rows ?? []
    return <section className="usage-results-panel" aria-label="Сравнение организаций">
        <div className="usage-results-toolbar">
            <div className="usage-results-heading">
                <h2>Сравнение организаций</h2>
                <p>Все организации, включая не использовавшие AI. Суммы за применённый период и фильтры; сортировка по известной стоимости.</p>
            </div>
            <button type="button" className="secondary-button" disabled={!rows.length}
                onClick={() => { try { downloadUsageCsv('organizations', rows); setExportError(false) } catch { setExportError(true) } }}>
                Скачать CSV страницы
            </button>
        </div>
        {exportError && <p role="alert">Не удалось создать CSV.</p>}
        {!current ? <p role="status">Загрузка сравнения…</p>
            : current.error ? <p role="alert">Сравнение недоступно. <button type="button" onClick={() => setRetry(x => x + 1)}>Повторить</button></p>
                : rows.length === 0 ? <p>Нет организаций на этой странице.</p>
                    : <div className="usage-organization-table">
                        <table className="usage-table">
                            <thead><tr><th>Организация</th><th>Подтверждённые токены</th><th>Известная стоимость</th><th>Полнота учёта</th></tr></thead>
                            <tbody>{rows.map(row => {
                                const empty = row.coverage.assistantMessages === '0'
                                const query = new URLSearchParams({ organizationId: row.organizationId, dateFrom, dateTo })
                                if (model) query.set('model', model)
                                if (userId) query.set('userId', userId)
                                return <tr key={row.organizationId}>
                                    <td><Link to={`/admin/usage?${query}`}>{row.organizationName}</Link></td>
                                    <td>{empty ? '—' : formatCount(row.totalTokens)}</td>
                                    <td>{empty || row.costUsd === null ? '—' : `${trimDecimal(row.costUsd)} USD`}</td>
                                    <td>{empty ? 'Нет данных' : <>
                                        Токены: {row.coverage.usageComplete === true ? 'полностью' : 'неполные данные'}<br />
                                        Стоимость: {row.coverage.pricingComplete === true ? 'полностью' : 'неполные данные'}
                                    </>}</td>
                                </tr>
                            })}</tbody>
                        </table>
                    </div>}
        <footer className="usage-results-footer">
            <button type="button" className="secondary-button" disabled={!current || page === 0} onClick={() => setPage(p => p - 1)}>Назад</button>
            <span>Страница {page + 1} · строк: {rows.length}</span>
            <button type="button" className="secondary-button" disabled={!current?.next} onClick={() => setPage(p => p + 1)}>Вперёд</button>
        </footer>
    </section>
}
