// frontend/src/features/usage/ui/UsagePhysicalReportPanel.test.tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getPhysicalUsageReport, type PhysicalUsageReport } from '../api/physicalUsageApi'
import { UsagePhysicalReportPanel } from './UsagePhysicalReportPanel'
import { serializePhysicalUsageCsv } from './physicalUsageCsv'

vi.mock('../api/physicalUsageApi', () => ({getPhysicalUsageReport: vi.fn()}))
const ORG = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const props = {
    dateFrom: '2026-11-01', dateTo: '2026-11-01', organizationId: ORG,
    userId: 'admin', superAdmin: false, scopedFilters: false, filtersDirty: false,
}
const sample: PhysicalUsageReport = {
    from: '2026-11-01T00:00:00Z', to: '2026-11-02T00:00:00Z', organizationId: ORG,
    aggregationBasis: 'PHYSICAL_ATTEMPT_STARTED_AT', planSelectionBasis: 'PLANS_WITH_PHYSICAL_START_IN_RANGE',
    currency: 'USD', physicalAttempts: '2', startedAttempts: '0', ambiguousAttempts: '1',
    knownExecutedAttempts: '1', knownNotExecutedAttempts: '0', knownRejectedAttempts: '0',
    costUnverifiableAttempts: '1', usageUnverifiableAttempts: '1', knownInputTokens: '9007199254740993',
    knownOutputTokens: '0', knownCostUsd: '0.000000000000000001', costComplete: false, usageComplete: false,
    plans: '1', costMatchedPlans: '0', costMismatchPlans: '0', unverifiablePlans: '1', fullyReconciled: false,
}
const empty: PhysicalUsageReport = {
    ...sample, physicalAttempts: '0', ambiguousAttempts: '0', knownExecutedAttempts: '0',
    costUnverifiableAttempts: '0', usageUnverifiableAttempts: '0', knownInputTokens: '0', knownCostUsd: '0',
    costComplete: true, usageComplete: true, plans: '0', unverifiablePlans: '0',
}

describe('Панель физического отчёта', () => {
    const request = vi.mocked(getPhysicalUsageReport)
    beforeEach(() => { request.mockReset() })
    it('показывает зелёный статус только для подтверждённой сверки', async () => {
        request.mockResolvedValue({...sample, costMatchedPlans: '1', unverifiablePlans: '0', fullyReconciled: true})
        render(<UsagePhysicalReportPanel {...props} />)
        fireEvent.click(screen.getByRole('button', {name: 'Показать сверку'}))
        expect(await screen.findByText(/Сверка пройдена/)).toBeVisible()
        expect(screen.queryByText(/финансовые расхождения/)).not.toBeInTheDocument()
    })
    it('выделяет подтверждённое расхождение', async () => {
        request.mockResolvedValue({...sample, costMismatchPlans: '1', unverifiablePlans: '0'})
        render(<UsagePhysicalReportPanel {...props} />)
        fireEvent.click(screen.getByRole('button', {name: 'Показать сверку'}))
        expect(await screen.findByText(/Обнаружены финансовые расхождения/)).toBeVisible()
        expect(screen.queryByText(/Сверка пройдена/)).not.toBeInTheDocument()
    })
    it('загружает лениво и показывает неполноту, а не ложный успех', async () => {
        request.mockResolvedValue(sample)
        render(<UsagePhysicalReportPanel {...props} />)
        expect(request).not.toHaveBeenCalled()
        fireEvent.click(screen.getByRole('button', {name: 'Показать сверку'}))
        expect(await screen.findByText(/Учёт неполный/)).toBeVisible()
        expect(screen.getByText('Сверка планов не подтверждена полностью.')).toBeVisible()
        expect(request).toHaveBeenCalledWith(expect.objectContaining({
            organizationId: null, expectedOrganizationId: ORG,
        }), expect.any(AbortSignal))
        expect(screen.getByRole('button', {name: 'Скачать CSV сверки'})).toBeEnabled()
    })
    it('не выдаёт пустую выборку за подтверждённый ноль', async () => {
        request.mockResolvedValue(empty)
        render(<UsagePhysicalReportPanel {...props} />)
        fireEvent.click(screen.getByRole('button', {name: 'Показать сверку'}))
        expect(await screen.findByText('Нет физических вызовов за период.')).toBeVisible()
        expect(screen.queryByText(/учтены полностью/)).not.toBeInTheDocument()
        expect(screen.getAllByText('—')).toHaveLength(2)
    })
    it('блокирует запрос при неподдерживаемых фильтрах и отменяет его при редактировании', () => {
        request.mockImplementation(() => new Promise(() => {}))
        const view = render(<UsagePhysicalReportPanel {...props} scopedFilters />)
        expect(screen.getByRole('button', {name: 'Показать сверку'})).toBeDisabled()
        expect(request).not.toHaveBeenCalled()
        view.rerender(<UsagePhysicalReportPanel {...props} superAdmin />)
        fireEvent.click(screen.getByRole('button', {name: 'Показать сверку'}))
        const [scope, signal] = request.mock.calls[0]
        expect(scope.organizationId).toBe(ORG)
        view.rerender(<UsagePhysicalReportPanel {...props} superAdmin filtersDirty />)
        expect(signal.aborted).toBe(true)
        expect(screen.queryByRole('button', {name: 'Скачать CSV сверки'})).not.toBeInTheDocument()
    })
    it('сохраняет точные значения, границы и отсутствие данных в CSV', () => {
        const csv = serializePhysicalUsageCsv(sample)
        expect(csv).toContain('"9007199254740993"')
        expect(csv).toContain('"0.000000000000000001"')
        expect(csv).toContain('"2026-11-02T00:00:00Z"')
        expect(csv).toContain('"Неполно"')
        expect(serializePhysicalUsageCsv(empty)).toContain('"";"";"";"USD";"Нет данных";"Нет данных"')
        const unknown = {...sample, costUnverifiableAttempts: '2', usageUnverifiableAttempts: '2',
            knownInputTokens: '0', knownCostUsd: '0'}
        expect(serializePhysicalUsageCsv(unknown)).toContain('"";"";"";"USD";"Неполно";"Неполно"')
    })
})
