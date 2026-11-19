// frontend/src/features/usage/ui/UsageOrganizationComparison.test.tsx
import { render, screen, fireEvent, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { it, expect, vi } from 'vitest'
import { UsageOrganizationRows } from './UsageOrganizationRows'
import { loadUsageReport } from '../lib/adminUsageReportLoader'
import { getUsageByOrganizations } from '../api/usage/usageClient'
import { parseUsageOrganizationSummary } from '../api/usage/usageParsers'
import { serializeUsageCsv } from './usageCsv'
import { getOrganizationBudgetStatus, parseBudgetStatus } from '../api/usage/usageBudget'

vi.mock('../api/usage/usageClient', () => ({ getUsageByOrganizations: vi.fn() }))
vi.mock('../api/usage/usageBudget', async importOriginal => ({
    ...await importOriginal<typeof import('../api/usage/usageBudget')>(),
    getOrganizationBudgetStatus: vi.fn(),
}))
const org = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const base = {
    organizationId: org, organizationName: '=FORMULA',
    responses: { assistantMessages: 1 },
    usage: { confirmedInputTokens: 3, confirmedOutputTokens: 5, availableUsageMessages: 1, partialUsageMessages: 0, missingUsageMessages: 0, usageNotApplicableMessages: 0 },
    cost: { knownCostUsd: '0.000000000001', currency: 'USD', pricedMessages: 1, freeMessages: 0, unpricedMessages: 0, pricingFailedMessages: 0, pricingNotApplicableMessages: 0 },
}
const row = parseUsageOrganizationSummary(base)
const empty = parseUsageOrganizationSummary({
    ...base, organizationId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', organizationName: 'Без данных',
    responses: { assistantMessages: 0 },
    usage: {...base.usage, confirmedInputTokens: 0, confirmedOutputTokens: 0, availableUsageMessages: 0},
    cost: {...base.cost, knownCostUsd: null, pricedMessages: 0},
})
const page = (index: number, content = [row, empty], totalPages = 2) =>
    ({page: index, size: 50, totalPages, totalElements: 51, content})

it('keeps unknown price and inactive organizations distinct and neutralizes CSV formulas', () => {
    const unknown = parseUsageOrganizationSummary({...base, cost: {...base.cost, knownCostUsd: null, pricedMessages: 0, unpricedMessages: 1}})
    expect(unknown.costUsd).toBeNull()
    expect(row.costUsd).toBe('0.000000000001')
    expect(() => parseUsageOrganizationSummary({...base, responses: {}})).toThrow()
    const csv = serializeUsageCsv('organizations', [row, empty, unknown])
    expect(csv).toContain("'=FORMULA")
    expect(csv).toContain('Нет данных')
    expect(csv).toContain('0.000000000001')
})

it('loads server-ranked pages and refuses unauthorized or inconsistent pages', async () => {
    const api = vi.mocked(getUsageByOrganizations)
    api.mockReset()
    const input = {tab: 'organizations' as const, page: 0, superAdmin: true, organizationId: null,
        filter: {dateFrom: '2026-11-01T00:00:00.000Z', dateTo: '2026-11-20T00:00:00.000Z', model: 'mock', order: 'tokens' as const},
        signal: new AbortController().signal}
    api.mockResolvedValueOnce(page(0)).mockResolvedValueOnce(page(0))
    expect(await loadUsageReport(input)).toMatchObject({rows: [row, empty], hasNext: true})
    expect(api).toHaveBeenCalledWith(0, 50, input.filter, expect.anything())
    await expect(loadUsageReport({...input, superAdmin: false})).rejects.toThrow()
    await expect(loadUsageReport({...input, organizationId: org})).rejects.toThrow()
    await expect(loadUsageReport({...input, page: 1})).rejects.toThrow()
})

it('distinguishes organization card from report drilldown and shows inactive rows honestly', () => {
    const onInspect = vi.fn()
    render(<MemoryRouter><UsageOrganizationRows rows={[row, empty]} onInspect={onInspect} userId="" reloadToken={0} disabled={false}/></MemoryRouter>)
    expect(screen.getByText('Без данных')).toBeVisible()
    expect(screen.getByText('Нет данных')).toBeVisible()
    expect(screen.getByText('Нет использования за период')).toBeVisible()
    expect(screen.queryByRole('button', {name: 'Без данных'})).not.toBeInTheDocument()
    expect(within(screen.getByRole('row', {name: /Без данных/})).getByRole('link', {name: 'Карточка организации'})).toHaveAttribute('href', expect.stringContaining(empty.organizationId))
    expect(screen.getAllByRole('link', { name: 'Карточка организации' })[0]).toHaveAttribute('href', expect.stringContaining(`organizationId=${org}`))
    fireEvent.click(screen.getByRole('button', {name: '=FORMULA'}))
    expect(onInspect).toHaveBeenCalledWith(row)
    expect(onInspect).toHaveBeenCalledTimes(1)
})

it('retries the actual scoped budget immediately and shows unset and unknown balances distinctly', async () => {
    const fetchBudget = vi.mocked(getOrganizationBudgetStatus)
    fetchBudget.mockReset()
    const snapshot = {
        organizationId: org, policyVersion: 2, policyEnabled: true, enforcement: 'HARD',
        periodStart: '2026-11-01T00:00:00Z', periodEnd: '2026-12-01T00:00:00Z', asOf: '2026-11-19T12:00:00Z',
        monthlyBudgetUsd: '10', knownActualUsd: '1', reservedUsd: '2', knownCommittedUsd: '3',
        unknownCommittedCostCount: 1, remainingUsd: null, status: 'UNKNOWN',
    }
    fetchBudget.mockRejectedValueOnce(new Error('internal SQL'))
        .mockResolvedValueOnce(parseBudgetStatus(snapshot))
        .mockResolvedValueOnce(parseBudgetStatus({...snapshot, organizationId: empty.organizationId,
            monthlyBudgetUsd: null, status: 'NO_LIMIT', unknownCommittedCostCount: 0}))
    render(<MemoryRouter><UsageOrganizationRows rows={[row, empty]} onInspect={vi.fn()}
        userId="admin" reloadToken={0} disabled={false}/></MemoryRouter>)
    const first = within(screen.getByRole('row', {name: /=FORMULA/}))
    const second = within(screen.getByRole('row', {name: /Без данных/}))
    expect(fetchBudget).not.toHaveBeenCalled()
    fireEvent.click(first.getByRole('button', {name: 'Показать бюджет'}))
    expect(await first.findByText('Бюджет недоступен')).toBeVisible()
    expect(screen.queryByText('internal SQL')).not.toBeInTheDocument()
    fireEvent.click(first.getByRole('button', {name: 'Повторить'}))
    expect(await first.findByText('Бюджет: 10 USD')).toBeVisible()
    expect(first.getByText('Резерв: 2 USD')).toBeVisible()
    expect(first.getByText(/Остаток: Неизвестен/)).toBeVisible()
    expect(fetchBudget.mock.calls.map(call => call[0])).toEqual([org, org])
    fireEvent.click(second.getByRole('button', {name: 'Показать бюджет'}))
    expect(await second.findByText('Бюджет: Не задан')).toBeVisible()
    expect(second.getByText('Остаток: Не рассчитывается')).toBeVisible()
    expect(fetchBudget.mock.calls.map(call => call[0])).toEqual([org, org, empty.organizationId])
})
