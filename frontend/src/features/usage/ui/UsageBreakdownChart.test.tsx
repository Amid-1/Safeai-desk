// frontend/src/features/usage/ui/UsageBreakdownChart.test.tsx
import { render, screen, fireEvent } from '@testing-library/react'
import type { PropsWithChildren } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { parseUsageSummary } from '../api/usageApi'
import { UsageBreakdownChart } from './UsageBreakdownChart'
import { parseUsageOrganizationSummary } from '../api/usage/usageParsers'

vi.mock('recharts', () => {
    const group = ({children}: PropsWithChildren) => <div>{children}</div>
    const nothing = () => null
    const entries = [
        ['ResponsiveContainer', group], ['BarChart', group], ['Bar', nothing],
        ['CartesianGrid', nothing], ['XAxis', nothing], ['YAxis', ({onClick}: {onClick?: (event: {value: number}) => void}) =>
            onClick ? <button onClick={() => onClick({value: 0})}>Выбрать подпись оси</button> : null], ['Tooltip', nothing],
    ] satisfies ReadonlyArray<readonly [keyof typeof import('recharts'), unknown]>
    return Object.fromEntries(entries)
})

const row = parseUsageSummary({
    userId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', userEmail: 'admin@example.test',
    model: 'mock-safeai', inputTokens: '9', outputTokens: '16', totalTokens: '25',
    costUsd: '0', currency: 'USD', usageComplete: true, pricingComplete: true,
})

describe('Usage breakdown: chart visibility and scope', () => {
    it.each(['summary', 'users', 'models'] as const)('shows the chart directly on %s', tab => {
        const {container} = render(<UsageBreakdownChart tab={tab} rows={[row]} hasMore={false}/>)
        expect(screen.getByText(/По выбранным фильтрам/)).toBeInTheDocument()
        expect(container.querySelector('#usage-breakdown-chart-content')).toBeInTheDocument()
        expect(screen.queryByRole('button')).not.toBeInTheDocument()
    })

    it('keeps a no-usage organization in comparison when its axis label is selected', () => {
        const organizationId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
        const empty = parseUsageOrganizationSummary({organizationId, organizationName: 'Сокол',
            responses: {assistantMessages: 0}, usage: {confirmedInputTokens: 0, confirmedOutputTokens: 0,
                availableUsageMessages: 0, partialUsageMessages: 0, missingUsageMessages: 0, usageNotApplicableMessages: 0},
            cost: {knownCostUsd: null, currency: 'USD', pricedMessages: 0, freeMessages: 0, unpricedMessages: 0,
                pricingFailedMessages: 0, pricingNotApplicableMessages: 0}})
        const onInspect = vi.fn()
        const {rerender} = render(<UsageBreakdownChart tab="organizations" rows={[empty]} onInspect={onInspect} relatedLinks/>)
        fireEvent.click(screen.getByRole('button', {name: 'Выбрать подпись оси'}))
        expect(screen.getByRole('status')).toHaveTextContent('Нет использования за выбранный период')
        expect(screen.getByRole('link', {name: 'Карточка организации'})).toHaveAttribute('href', expect.stringContaining(organizationId))
        expect(screen.queryByRole('button', {name: 'Детализация использования'})).not.toBeInTheDocument()
        expect(onInspect).not.toHaveBeenCalled()
        const replacement = {...empty, organizationName: 'Другая'}
        rerender(<UsageBreakdownChart tab="organizations" rows={[replacement]} onInspect={onInspect} relatedLinks/>)
        expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })

    it('warns about unloaded pages only when there are more rows', () => {
        const {rerender} = render(<UsageBreakdownChart tab="users" rows={[row]} hasMore/>)
        expect(screen.getByText(/Только на текущей странице отчёта/)).toBeInTheDocument()
        rerender(<UsageBreakdownChart tab="users" rows={[row]} hasMore={false}/>)
        expect(screen.queryByText(/Только на текущей странице отчёта/)).not.toBeInTheDocument()
    })
})
