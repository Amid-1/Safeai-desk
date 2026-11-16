// frontend/src/features/usage/ui/UsageBreakdownChart.test.tsx
import { render, screen } from '@testing-library/react'
import type { PropsWithChildren } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { parseUsageSummary } from '../api/usageApi'
import { UsageBreakdownChart } from './UsageBreakdownChart'

vi.mock('recharts', () => {
    const group = ({children}: PropsWithChildren) => <div>{children}</div>
    const nothing = () => null
    const entries = [
        ['ResponsiveContainer', group], ['BarChart', group], ['Bar', nothing],
        ['CartesianGrid', nothing], ['XAxis', nothing], ['YAxis', nothing], ['Tooltip', nothing],
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

    it('warns about unloaded pages only when there are more rows', () => {
        const {rerender} = render(<UsageBreakdownChart tab="users" rows={[row]} hasMore/>)
        expect(screen.getByText(/Только на текущей странице отчёта/)).toBeInTheDocument()
        rerender(<UsageBreakdownChart tab="users" rows={[row]} hasMore={false}/>)
        expect(screen.queryByText(/Только на текущей странице отчёта/)).not.toBeInTheDocument()
    })
})
