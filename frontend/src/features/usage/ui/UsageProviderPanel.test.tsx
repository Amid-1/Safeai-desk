// frontend/src/features/usage/ui/UsageProviderPanel.test.tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getUsageProviders } from '../api/usageProviderApi'
import { UsageProviderPanel } from './UsageProviderPanel'

vi.mock('../api/usageProviderApi', () => ({getUsageProviders: vi.fn()}))
const filter = {kind: 'providers', dateFrom: '2026-11-01', dateTo: '2026-11-21', organizationId: null}
const props = {filter, scopeKey: 'global', blocked: false, dirty: false}
describe('Отчёт по провайдерам', () => {
    beforeEach(() => vi.resetAllMocks())
    it('не отображает неизвестную стоимость как бесплатный вызов', async () => {
        vi.mocked(getUsageProviders).mockResolvedValue([{provider: 'mock', attempts: '1', unresolved: '1',
            unknownUsage: '1', unknownCost: '1', knownCostUsd: '0', input: '0', output: '0'}])
        render(<UsageProviderPanel {...props} />)
        expect(await screen.findByText('mock')).toBeVisible()
        expect(screen.getAllByText('—')).toHaveLength(3)
        expect(screen.queryByText('0 USD')).not.toBeInTheDocument()
        expect(screen.getByRole('button', {name: 'Подготовить CSV по фильтрам'})).toBeEnabled()
    })
    it('не вызывает сервер для неподдерживаемого фильтра бюджета', () => {
        render(<UsageProviderPanel {...props} blocked />)
        expect(screen.getByText(/Снимите фильтр бюджета/)).toBeVisible()
        expect(getUsageProviders).not.toHaveBeenCalled()
    })
    it('позволяет повторить недоступный отчёт', async () => {
        vi.mocked(getUsageProviders).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([])
        render(<UsageProviderPanel {...props} />)
        fireEvent.click(await screen.findByRole('button', {name: 'Повторить'}))
        expect(await screen.findByText(/Нет физических вызовов/)).toBeVisible()
    })
})
