// frontend/src/features/usage/ui/UsageOrganizationRows.tsx
import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { UsageOrganizationSummary } from '../api/usageApi'
import { useUsageBudget } from '../controller/useUsageBudget'
import { formatCount, trimDecimal } from '../lib/adminUsagePresentation'

export function UsageOrganizationBudget({id, userId, reloadToken}: {id: string; userId: string; reloadToken: number}) {
    const [open, setOpen] = useState(false)
    const [retry, setRetry] = useState(0)
    const budget = useUsageBudget(open ? userId : '', id, reloadToken, retry)
    const money = (value: string | null) => value === null ? '—' : trimDecimal(value) + ' USD'
    if (!open) return <button type="button" className="secondary-button" onClick={() => setOpen(true)}>Показать бюджет</button>
    if (budget.loading) return <span role="status">Загрузка…</span>
    if (budget.error || !budget.policy) return <div className="usage-budget-cell">
        <span role="status">Бюджет недоступен</span>
        {budget.errorMessage && <small>{budget.errorMessage}</small>}
        <button type="button" className="secondary-button" onClick={() => setRetry(value => value + 1)}>Повторить</button>
    </div>
    const p = budget.policy
    const ratio = p.monthlyBudgetUsd !== null && (p.status === 'KNOWN' || p.status === 'EXCEEDED')
        ? budgetRatio(p.knownCommittedUsd, p.monthlyBudgetUsd) : null
    return <div className="usage-budget-cell">
        <small>{p.periodStart.slice(0, 7)} UTC · {p.policyEnabled ? 'Правила включены' : 'Правила отключены'}</small>
        <span>Бюджет: {p.monthlyBudgetUsd === null ? 'Не задан' : money(p.monthlyBudgetUsd)}</span>
        {p.status === 'UNCONFIGURED' && <small>Правила организации ещё не настроены.</small>}
        <span>Известные затраты: {money(p.knownActualUsd)}</span>
        <span>Резерв: {money(p.reservedUsd)}</span>
        <span>Остаток: {p.remainingUsd !== null ? money(p.remainingUsd)
            : p.status === 'UNKNOWN' || p.status === 'EXCEEDED_LOWER_BOUND'
                ? 'Неизвестен — есть обязательства без цены' : 'Не рассчитывается'}</span>
        {ratio !== null && <><meter min={0} max={100} value={Number(ratio > 100n ? 100n : ratio)} aria-label="Использование бюджета"/>
            <span>Занято: {ratio.toString()}% (включая резерв)</span></>}
        {['EXCEEDED', 'EXCEEDED_LOWER_BOUND'].includes(p.status) && <strong>Превышен</strong>}
    </div>
}

function budgetRatio(committed: string, budget: string): bigint | null {
    const [cw, cf = ''] = committed.split('.'), [bw, bf = ''] = budget.split('.')
    const scale = Math.max(cf.length, bf.length)
    const denominator = BigInt(bw + bf.padEnd(scale, '0'))
    return denominator === 0n ? null : BigInt(cw + cf.padEnd(scale, '0')) * 100n / denominator
}

export function UsageOrganizationRows({rows, onInspect, userId, reloadToken, disabled}: {
    rows: UsageOrganizationSummary[]; onInspect: (row: UsageOrganizationSummary) => void
    userId: string; reloadToken: number; disabled: boolean
}) {
    return <table className="usage-table">
        <thead><tr><th scope="col">Организация</th><th scope="col">Вход</th><th scope="col">Выход</th>
            <th scope="col">Всего токенов</th><th scope="col">Известная стоимость</th>
            <th scope="col">Полнота учёта</th><th scope="col">Месячный бюджет (UTC)</th></tr></thead>
        <tbody>{rows.map(row => {
            const empty = row.coverage.assistantMessages === '0'
            const amount = (value: string) => empty ? '—' : formatCount(value)
            return <tr key={row.organizationId}>
                <td>{empty ? <strong>{row.organizationName}</strong>
                    : <button type="button" className="usage-drilldown" disabled={disabled} onClick={() => onInspect(row)}>{row.organizationName}</button>}
                    {empty && <div><small>Нет использования за период</small></div>}
                    <div><Link to={'/admin/organizations?organizationId=' + encodeURIComponent(row.organizationId)}>Карточка организации</Link></div></td>
                <td>{amount(row.inputTokens)}</td><td>{amount(row.outputTokens)}</td><td>{amount(row.totalTokens)}</td>
                <td>{empty || row.costUsd === null ? '—' : trimDecimal(row.costUsd) + ' USD'}</td>
                <td>{empty ? 'Нет данных' : <><span>Токены: {row.coverage.usageComplete === true ? 'полностью' : 'неполные данные'}</span><br/>
                    <span>Стоимость: {row.coverage.pricingComplete === true ? 'полностью' : 'неполные данные'}</span></>}</td>
                <td><UsageOrganizationBudget id={row.organizationId} userId={userId} reloadToken={reloadToken}/></td>
            </tr>
        })}</tbody>
    </table>
}
