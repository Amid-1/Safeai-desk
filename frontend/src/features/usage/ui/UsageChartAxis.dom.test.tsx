// frontend/src/features/usage/ui/UsageChartAxis.dom.test.tsx
import { fireEvent, render, screen, within } from '@testing-library/react'
import { ResponsiveContainer } from 'recharts'
import { describe, expect, it, vi } from 'vitest'
import { parseUsageOrganizationSummary } from '../api/usage/usageParsers'
import { UsageBreakdownChart } from './UsageBreakdownChart'
import { UsageDailyChart } from './UsageDailyChart'
import type { UsageDailySummary } from '../api/usageApi'

const organizationId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const empty = parseUsageOrganizationSummary({
    organizationId, organizationName: 'Сокол', responses: {assistantMessages: 0},
    usage: {confirmedInputTokens: 0, confirmedOutputTokens: 0, availableUsageMessages: 0,
        partialUsageMessages: 0, missingUsageMessages: 0, usageNotApplicableMessages: 0},
    cost: {knownCostUsd: null, currency: 'USD', pricedMessages: 0, freeMessages: 0,
        unpricedMessages: 0, pricingFailedMessages: 0, pricingNotApplicableMessages: 0},
})

// Real Recharts SVG, including its actual axis event wiring. The outer fixed
// container supplies geometry that JSDOM cannot measure; no chart mocks.
describe('Usage chart axis labels (real SVG)', () => {
    it('selects the actual category text for an organization with no usage', async () => {
        const onInspect = vi.fn()
        const {rerender} = render(<ResponsiveContainer width={800} height={240}>
            <UsageBreakdownChart tab="organizations" rows={[empty]} onInspect={onInspect} relatedLinks/>
        </ResponsiveContainer>)
        const surface = screen.getByRole('application')
        const label = await within(surface).findByText('Сокол', {exact: true})
        expect(label.closest('text')).toHaveClass('usage-chart-action-tick')
        fireEvent.click(label)
        const selection = screen.getByRole('region', {name: 'Выбранная запись графика'})
        expect(selection).toHaveTextContent('Нет использования за выбранный период')
        expect(within(selection).getByRole('link', {name: 'Карточка организации'}))
            .toHaveAttribute('href', '/admin/organizations?organizationId=' + organizationId)
        expect(within(selection).queryByRole('button', {name: 'Детализация использования'})).not.toBeInTheDocument()
        expect(onInspect).not.toHaveBeenCalled()
        rerender(<ResponsiveContainer width={800} height={240}>
            <UsageBreakdownChart tab="organizations" rows={[empty]} relatedLinks/>
        </ResponsiveContainer>)
        expect(within(screen.getByRole('application')).getByText('Сокол', {exact: true}).closest('text'))
            .not.toHaveClass('usage-chart-action-tick')
        expect(screen.queryByRole('region', {name: 'Выбранная запись графика'})).not.toBeInTheDocument()
    })

    it('selects the actual date text only while date inspection is enabled', async () => {
        const row: UsageDailySummary = {...empty, usageDate: '2026-11-15', aggregationZone: 'UTC'}
        const onInspectRange = vi.fn()
        const onMetricChange = vi.fn()
        const {rerender} = render(<ResponsiveContainer width={800} height={240}>
            <UsageDailyChart rows={[row]} metric="tokens" onMetricChange={onMetricChange}
                onInspectRange={onInspectRange}/>
        </ResponsiveContainer>)
        const label = await within(screen.getByRole('application')).findByText('15.11.2026', {exact: true})
        expect(label.closest('text')).toHaveClass('usage-chart-action-tick')
        fireEvent.click(label)
        expect(onInspectRange).toHaveBeenCalledExactlyOnceWith('2026-11-15', '2026-11-15')
        rerender(<ResponsiveContainer width={800} height={240}>
            <UsageDailyChart rows={[row]} metric="tokens" onMetricChange={onMetricChange}/>
        </ResponsiveContainer>)
        const disabledLabel = within(screen.getByRole('application')).getByText('15.11.2026', {exact: true})
        expect(disabledLabel.closest('text')).not.toHaveClass('usage-chart-action-tick')
        fireEvent.click(disabledLabel)
        expect(onInspectRange).toHaveBeenCalledTimes(1)
    })
})
