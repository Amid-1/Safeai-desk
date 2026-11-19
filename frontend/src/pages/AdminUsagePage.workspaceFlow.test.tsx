// frontend/src/pages/AdminUsagePage.workspaceFlow.test.tsx
import { fireEvent, render, screen } from '@testing-library/react'
import type { PropsWithChildren } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { parseUsageSummary } from '../features/usage/api/usageApi'
import { useAdminUsageReport } from '../features/usage/controller/useAdminUsageReport'
import { downloadUsageCsv } from '../features/usage/ui/usageCsv'
import AdminUsagePage from './AdminUsagePage'

vi.mock('../features/usage/controller/useAdminUsageReport', () => ({ useAdminUsageReport: vi.fn() }))
vi.mock('../features/usage/ui/usageCsv', () => ({ downloadUsageCsv: vi.fn() }))
vi.mock('recharts', () => {
    const container = ({ children }: PropsWithChildren) => <div>{children}</div>
    const nothing = () => null
    return {
        ResponsiveContainer: container, BarChart: container, CartesianGrid: nothing,
        XAxis: nothing, YAxis: nothing, Tooltip: nothing, Legend: nothing, Bar: nothing,
    }
})

const row = parseUsageSummary({
    userId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', userEmail: 'admin@example.test',
    model: 'mock-safeai', inputTokens: '9', outputTokens: '16', totalTokens: '25',
    costUsd: '0', currency: 'USD', usageComplete: true, pricingComplete: true,
})

const fixture = {
    reportIdentity: 'admin', reloadToken: 0,
    tab: 'summary', rows: [row], loading: false, error: '', page: 0,
    hasNext: false, hasPrevious: false, filterError: '', filtersDirty: false,
    appliedDateFrom: '2026-09-21', appliedDateTo: '2026-10-20',
    appliedModel: '', scopeLabel: 'Моя организация', isSuperAdmin: false,
    appliedUserId: '', appliedUserLabel: '', appliedOrganizationId: '',
    draftDateFrom: '2026-09-21', draftDateTo: '2026-10-20', draftModel: '',
    draftOrganizationId: '', draftOrganizationName: '', organizationOptions: [],
    organizationSearchPending: false, organizationSearchError: '', modelPickerOptions: [],
    modelOptionsLoading: false, modelOptionsError: '', setOrganizationQuery: vi.fn(),
    changeDraftDateFrom: vi.fn(), changeDraftDateTo: vi.fn(), changeDraftModel: vi.fn(),
    changeDraftOrganization: vi.fn(), applyFilters: vi.fn(), inspectModel: vi.fn(),
    inspectUser: vi.fn(), clearFilter: vi.fn(),
    inspectDay: vi.fn(), resetFilters: vi.fn(), selectTab: vi.fn(), goToPage: vi.fn(), reload: vi.fn(),
    inspectRow: vi.fn(), inspectRange: vi.fn(), order: 'tokens' as const, changeOrder: vi.fn(),
    totals: { rows: [row], loading: false, error: '', hasNext: false, hasPrevious: false },
    budget: { scoped: false, loading: false, error: false, policy: null, errorMessage: '' },
    exportFiltered: vi.fn(), exportPending: false, filteredExportError: '',
} satisfies ReturnType<typeof useAdminUsageReport>

describe('Usage dashboard', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        vi.mocked(useAdminUsageReport).mockReturnValue(fixture)
    })
    it('renders one data view and fixed controls; expands and restores the workspace', () => {
        const {container} = render(<AdminUsagePage />)
        expect(screen.getByRole('table')).toBeVisible()
        expect(screen.queryByRole('button', {name: 'Развернуть'})).not.toBeInTheDocument()
        expect(screen.queryByRole('region', { name: 'Расход токенов по пользователям и моделям' })).not.toBeInTheDocument()
        expect(screen.queryByRole('separator')).not.toBeInTheDocument()
        expect(screen.getByText('100%')).toBeVisible()
        expect(screen.getByText('Вход: 9 · Выход: 16')).toBeVisible()
        fireEvent.click(screen.getByRole('button', {name: 'Изменить фильтры'}))
        expect(screen.getByLabelText('Начало периода')).toBeVisible()
        fireEvent.click(screen.getByRole('button', {name: 'График'}))
        expect(screen.queryByRole('table')).not.toBeInTheDocument()
        expect(screen.getByRole('region', { name: 'Расход токенов по пользователям и моделям' })).toBeVisible()
        fireEvent.click(screen.getByRole('button', {name: 'Развернуть'}))
        expect(container.querySelector('.usage-page')).toHaveClass('usage-page--expanded')
        expect(screen.getByText('100%')).toBeVisible()
        expect(screen.getByLabelText('Начало периода')).toBeVisible()
        expect(screen.getByRole('tab', {name: 'Детализация'})).toBeVisible()
        expect(screen.getByText('Все записи показаны')).toBeVisible()
        fireEvent.click(screen.getByRole('button', {name: 'Свернуть'}))
        expect(screen.getByLabelText('Начало периода')).toBeVisible()
        expect(screen.getByText('100%')).toBeVisible()
        fireEvent.click(screen.getByRole('button', {name: 'Таблица'}))
        expect(screen.getByRole('table')).toBeVisible()
        expect(screen.queryByRole('button', {name: 'Развернуть'})).not.toBeInTheDocument()
        expect(screen.queryByRole('region', { name: 'Расход токенов по пользователям и моделям' })).not.toBeInTheDocument()
    })
    it('drills into user and model; active values are not redundant links', () => {
        const view = render(<AdminUsagePage />)
        fireEvent.click(screen.getByRole('button', {name: 'admin@example.test →'}))
        expect(fixture.inspectUser).toHaveBeenCalledWith(row.userId, row.userEmail)
        fireEvent.click(screen.getByRole('button', {name: 'mock-safeai →'}))
        expect(fixture.inspectModel).toHaveBeenCalledWith(row.model)
        vi.mocked(useAdminUsageReport).mockReturnValue({
            ...fixture, appliedUserId: row.userId, appliedUserLabel: row.userEmail, appliedModel: row.model,
        })
        view.rerender(<AdminUsagePage />)
        expect(screen.queryByRole('button', {name: 'admin@example.test →'})).not.toBeInTheDocument()
        expect(screen.queryByRole('button', {name: 'mock-safeai →'})).not.toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', {name: 'Сбросить фильтр по пользователю'}))
        expect(fixture.clearFilter).toHaveBeenCalledWith('user')
    })
    it('provides distinct scoped card links for SUPER_ADMIN, but not tenant admins', () => {
        const org = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
        vi.mocked(useAdminUsageReport).mockReturnValue({...fixture, isSuperAdmin: true, appliedOrganizationId: org})
        const view = render(<AdminUsagePage />)
        expect(screen.getByRole('link', {name:'Карточка пользователя'})).toHaveAttribute('href', `/admin/users?userId=${row.userId}`)
        expect(screen.getByRole('link', {name:'Карточка модели'})).toHaveAttribute('href', `/admin/models?section=catalog&usageModel=mock-safeai&organizationId=${org}`)
        fireEvent.click(screen.getByRole('button', {name: 'mock-safeai →'}))
        expect(fixture.inspectModel).toHaveBeenCalledWith(row.model)
        vi.mocked(useAdminUsageReport).mockReturnValue(fixture)
        view.rerender(<AdminUsagePage />)
        expect(screen.queryByRole('link', {name:'Карточка пользователя'})).toBeNull()
        expect(screen.queryByRole('link', {name:'Карточка модели'})).toBeNull()
    })
    it('retains pagination and labels the scope of chart and export on paged results', () => {
        vi.mocked(useAdminUsageReport).mockReturnValue({...fixture, hasNext: true})
        render(<AdminUsagePage />)
        expect(screen.getByText(/Весь выбранный период/)).toBeVisible()
        fireEvent.click(screen.getByRole('button', {name: 'График'}))
        fireEvent.click(screen.getByRole('button', {name: 'Вперёд'}))
        expect(fixture.goToPage).toHaveBeenCalledWith(1)
        fireEvent.click(screen.getByRole('button', {name: 'Скачать CSV страницы'}))
        expect(downloadUsageCsv).toHaveBeenCalledWith('summary', [row])
        fireEvent.click(screen.getByRole('tab', {name: 'По моделям'}))
        expect(fixture.selectTab).toHaveBeenCalledWith('models')
    })
    it('preserves the controlled daily metric across table and chart remounts', () => {
        vi.mocked(useAdminUsageReport).mockReturnValue({
            ...fixture,
            tab: 'daily',
            rows: [{ ...row, usageDate: '2026-09-27', aggregationZone: 'UTC' }],
        })
        render(<AdminUsagePage />)
        fireEvent.click(screen.getByRole('button', { name: 'График' }))
        const metric = screen.getByLabelText('Показатель')
        expect(metric).toHaveValue('tokens')
        fireEvent.change(metric, { target: { value: 'cost' } })
        expect(metric).toHaveValue('cost')
        fireEvent.click(screen.getByRole('button', { name: 'Таблица' }))
        expect(screen.queryByLabelText('Показатель')).not.toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: 'График' }))
        expect(screen.getByLabelText('Показатель')).toHaveValue('cost')
        fireEvent.click(screen.getByRole('button', { name: 'Развернуть' }))
        expect(screen.getByLabelText('Показатель')).toHaveValue('cost')
    })
    it.each(['summary', 'users', 'models', 'daily'] as const)(
        'resizes the chart without hiding report controls on %s', tab => {
            vi.mocked(useAdminUsageReport).mockReturnValue({
                ...fixture, tab,
                rows: tab === 'daily' ? [{ ...row, usageDate: '2026-09-27', aggregationZone: 'UTC' }] : [row],
            })
            const {container} = render(<AdminUsagePage />)
            fireEvent.click(screen.getByRole('button', {name: 'График'}))
            fireEvent.click(screen.getByRole('button', {name: 'Развернуть'}))
            expect(container.querySelector('.usage-page')).toHaveClass('usage-page--expanded')
            expect(screen.getByText('100%')).toBeVisible()
            expect(screen.getByRole('tablist')).toBeVisible()
            expect(screen.getByRole('button', {name: 'Изменить фильтры'})).toBeVisible()
            fireEvent.keyDown(screen.getByRole('button', {name: 'Свернуть'}), {key: 'Escape'})
            expect(container.querySelector('.usage-page')).not.toHaveClass('usage-page--expanded')
            expect(screen.getByRole('button', {name: 'Развернуть'})).toBeVisible()
        },
    )
    it('keeps the report visible after export failure and supports retry', () => {
        vi.mocked(downloadUsageCsv).mockImplementationOnce(() => { throw new Error('CSV') })
        render(<AdminUsagePage />)
        const csv = screen.getByRole('button', {name: 'Скачать CSV страницы'})
        fireEvent.click(csv)
        expect(screen.getByRole('alert')).toHaveTextContent('Не удалось создать CSV')
        expect(screen.getByRole('table')).toBeVisible()
        fireEvent.click(csv)
        expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })
    it('does not represent incomplete prices as zero or incomplete coverage as 100%', () => {
        const unknown = {
            ...row, costUsd: null, coverage: {...row.coverage, pricingComplete: false},
        }
        vi.mocked(useAdminUsageReport).mockReturnValue({...fixture, rows: [unknown],
            totals: {...fixture.totals, rows: [unknown]}})
        render(<AdminUsagePage />)
        expect(screen.getByText('0%')).toBeVisible()
        expect(screen.queryByText('100%')).not.toBeInTheDocument()
        expect(screen.queryByText('0 USD')).not.toBeInTheDocument()
        expect(screen.getByText('0 из 1 дней с полным учётом токенов и цены')).toBeVisible()
    })
    it('does not replace unavailable global totals with the loaded page', () => {
        vi.mocked(useAdminUsageReport).mockReturnValue({...fixture, hasNext: true,
            totals: {...fixture.totals, rows: [], error: 'Unavailable'}})
        render(<AdminUsagePage />)
        expect(screen.getByText('Общие итоги недоступны')).toBeVisible()
        expect(screen.queryByText('100%')).not.toBeInTheDocument()
        expect(screen.getByRole('table')).toBeVisible()
        fireEvent.click(screen.getByRole('button', {name: 'CSV по фильтрам (до 100)'}))
        expect(fixture.exportFiltered).toHaveBeenCalledOnce()
    })
    it('shows loading, error with retry and empty state inside the data region', () => {
        vi.mocked(useAdminUsageReport).mockReturnValue({...fixture, loading: true})
        const view = render(<AdminUsagePage />)
        expect(screen.getByText('Загрузка отчёта…')).toBeVisible()
        expect(screen.getByRole('button', {name: 'Скачать CSV страницы'})).toBeDisabled()
        vi.mocked(useAdminUsageReport).mockReturnValue({...fixture, error: 'Ошибка сети'})
        view.rerender(<AdminUsagePage />)
        expect(screen.getByText('Ошибка сети')).toBeVisible()
        fireEvent.click(screen.getByRole('button', {name: 'Повторить'}))
        expect(fixture.reload).toHaveBeenCalledOnce()
        vi.mocked(useAdminUsageReport).mockReturnValue({...fixture, rows: []})
        view.rerender(<AdminUsagePage />)
        expect(screen.getByText('За этот период использования нет')).toBeVisible()
        expect(screen.getByText('Нет записей')).toBeVisible()
    })
})
