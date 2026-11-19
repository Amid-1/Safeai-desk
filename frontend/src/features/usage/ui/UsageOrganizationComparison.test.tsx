// frontend/src/features/usage/ui/UsageOrganizationComparison.test.tsx
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { it, expect, vi } from 'vitest'
import { UsageOrganizationComparison } from './UsageOrganizationComparison'
import { getUsageByOrganizations } from '../api/usage/usageClient'
import { parseUsageOrganizationSummary } from '../api/usage/usageParsers'
import { serializeUsageCsv } from './usageCsv'

vi.mock('../api/usage/usageClient', () => ({ getUsageByOrganizations: vi.fn() }))
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

it('requests paginated server rows using applied filters, makes organization links and retries failed pages', async () => {
    const api = vi.mocked(getUsageByOrganizations)
    api.mockReset()
    api.mockResolvedValueOnce(page(0)).mockRejectedValueOnce(new Error('denied')).mockResolvedValueOnce(page(1, [row]))
    render(<MemoryRouter><UsageOrganizationComparison dateFrom="2026-11-01" dateTo="2026-11-19" model="mock" userId="" /></MemoryRouter>)
    expect(await screen.findByText('Без данных')).toBeVisible()
    expect(screen.getByText('Нет данных')).toBeVisible()
    expect(screen.getByRole('link', { name: '=FORMULA' })).toHaveAttribute('href', expect.stringContaining(`organizationId=${org}`))
    expect(api).toHaveBeenCalledWith(0, 50, expect.objectContaining({
        dateFrom: '2026-11-01T00:00:00.000Z', dateTo: '2026-11-20T00:00:00.000Z', model: 'mock',
    }), expect.anything())
    fireEvent.click(screen.getByRole('button', {name: 'Вперёд'}))
    expect(await screen.findByRole('alert')).toHaveTextContent('Сравнение недоступно')
    fireEvent.click(screen.getByRole('button', {name: 'Повторить'}))
    await waitFor(() => expect(screen.getByRole('link', {name: '=FORMULA'})).toBeVisible())
    expect(api).toHaveBeenLastCalledWith(1, 50, expect.anything(), expect.anything())
})
