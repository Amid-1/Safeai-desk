// frontend/src/features/usage/ui/UsageDailyChart.test.tsx
import { fireEvent, render, screen } from '@testing-library/react'
import type { PropsWithChildren } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { UsageDailySummary } from '../api/usageApi'
import { UsageDailyChart } from './UsageDailyChart'

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

describe('UsageDailyChart', () => {
    it('shows an accessible chart and switches metrics without changing rows', () => {
        const rows = [day]
        render(<UsageDailyChart rows={rows} />)
        expect(screen.getByRole('region', { name: 'График использования AI по дням' })).toBeTruthy()
        expect(screen.getByText('Входные токены')).toBeTruthy()
        expect(screen.getByText('Выходные токены')).toBeTruthy()
        fireEvent.change(screen.getByLabelText('Показатель'), { target: { value: 'total' } })
        expect(screen.getByText('Всего токенов')).toBeTruthy()
        fireEvent.change(screen.getByLabelText('Показатель'), { target: { value: 'cost' } })
        expect(screen.getByText('Известная стоимость, USD')).toBeTruthy()
        expect(rows[0]).toEqual(day)
    })

    it('does not render an imprecise bar when token counts exceed JS safe integers', () => {
        render(<UsageDailyChart rows={[{
            ...day,
            inputTokens: '9007199254740993',
            outputTokens: '1',
            totalTokens: '9007199254740994',
        }]} />)
        expect(screen.getByRole('status').textContent).toContain('слишком велики')
    })
})
