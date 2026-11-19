// frontend/src/features/usage/ui/UsageDailyChart.test.tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { useState, type ComponentProps, type PropsWithChildren } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { UsageDailySummary } from '../api/usageApi'
import { UsageDailyChart, type UsageDailyMetric } from './UsageDailyChart'
import { buildDailySeries } from './usageDailyChartData'

// JSDOM cannot measure ResponsiveContainer reliably. Mock only chart primitives;
// retain accessible controls, real chart selection logic and exact input rows.
vi.mock('recharts', () => {
    const renderChildren = ({ children }: PropsWithChildren) => <div>{children}</div>
    const renderNothing = () => null
    const renderBarLabel = ({ name }: { name: string }) => <span>{name}</span>

    // Recharts exports are looked up dynamically by Vitest. A typed entry list
    // prevents IDE false-positive "Unused property" warnings while checking
    // every mocked export name against the real Recharts module.
    const exports = [
        ['ResponsiveContainer', renderChildren],
        ['BarChart', renderChildren],
        ['CartesianGrid', renderNothing],
        ['XAxis', renderNothing],
        ['YAxis', renderNothing],
        ['Tooltip', renderNothing],
        ['Legend', renderNothing],
        ['Bar', renderBarLabel],
    ] satisfies ReadonlyArray<readonly [keyof typeof import('recharts'), unknown]>

    return Object.fromEntries(exports)
})

const coverage: UsageDailySummary['coverage'] = {
    assistantMessages: '1', completedResponses: '1', refusedResponses: '0',
    incompleteResponses: '0', failedMessages: '0', unclassifiedResponses: '0',
    availableUsageMessages: '1', partialUsageMessages: '0', missingUsageMessages: '0',
    usageNotApplicableMessages: '0', pricedMessages: '1', freeMessages: '0',
    unpricedMessages: '0', pricingFailedMessages: '0', pricingNotApplicableMessages: '0',
    usageComplete: true, pricingComplete: true,
}

const day: UsageDailySummary = {
    usageDate: '2026-09-27', aggregationZone: 'UTC',
    inputTokens: '167', outputTokens: '102', totalTokens: '269',
    partialInputTokens: '0', partialOutputTokens: '0', partialTotalTokens: '0',
    costUsd: '0', currency: 'USD', coverage,
}

function DailyChart(props: Omit<ComponentProps<typeof UsageDailyChart>, 'metric' | 'onMetricChange'>) {
    const [metric, setMetric] = useState<UsageDailyMetric>('tokens')
    return <UsageDailyChart {...props} metric={metric} onMetricChange={setMetric} />
}

describe('UsageDailyChart', () => {
    it('shows an accessible chart and switches metrics without changing rows', () => {
        const rows = [day]
        render(<DailyChart rows={rows} />)
        expect(screen.getByRole('region', { name: 'График использования AI по дням' })).toBeTruthy()
        expect(screen.getByText('Входные токены')).toBeTruthy()
        expect(screen.getByText('Выходные токены')).toBeTruthy()
        fireEvent.change(screen.getByLabelText('Показатель'), { target: { value: 'total' } })
        expect(screen.getByText('Всего токенов')).toBeTruthy()
        fireEvent.change(screen.getByLabelText('Показатель'), { target: { value: 'cost' } })
        // The label occurs in both <option> and the chart; assert the chart.
        expect(screen.getByRole('region', { name: 'График использования AI по дням' })
            .querySelector('.usage-chart-viewport'))
            .toHaveTextContent('Известная стоимость, USD')
        expect(rows[0]).toEqual(day)
    })

    it('does not render an imprecise bar when token counts exceed JS safe integers', () => {
        render(<DailyChart rows={[{
            ...day,
            inputTokens: '9007199254740993',
            outputTokens: '1',
            totalTokens: '9007199254740994',
        }]} />)
        expect(screen.getByRole('status').textContent).toContain('слишком велики')
        fireEvent.change(screen.getByLabelText('Показатель'), { target: { value: 'cost' } })
        expect(screen.queryByRole('status')).not.toBeInTheDocument()
        expect(screen.getByRole('region').querySelector('.usage-chart-viewport')).toBeInTheDocument()
    })

    it('preserves calendar gaps without manufacturing zero-cost or zero-token records', () => {
        const data = buildDailySeries([day], '2026-09-26', '2026-09-28')
        expect(data.map(item => [item.date, item.total, item.cost])).toEqual([
            ['26.09.2026', null, null], ['27.09.2026', 269, 0], ['28.09.2026', null, null],
        ])
    })

    it('groups exact decimals into clipped UTC weeks and retains entirely empty buckets', () => {
        const data = buildDailySeries([{...day, usageDate: '2026-09-25', costUsd: '0.000000000001'},
            {...day, usageDate: '2026-09-26', costUsd: '0.000000000002'}], '2026-09-25', '2026-10-04', 'week')
        expect(data.map(item => [item.start, item.end, item.exactTotal, item.exactCost])).toEqual([
            ['2026-09-25', '2026-09-27', '538', '0.000000000003'],
            ['2026-09-28', '2026-10-04', null, null],
        ])
    })

    it('renders only the metric control, leaving view switching to the dashboard', () => {
        render(<DailyChart rows={[day]} />)
        expect(screen.getByLabelText('Показатель')).toHaveValue('tokens')
        expect(screen.queryByRole('button')).not.toBeInTheDocument()
    })
})
