// frontend/src/pages/AdminDashboardPage.test.tsx
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getOrganizations } from '../entities/organization/api/organizationApi'
import { getUserStatistics } from '../entities/user/api/userApi'
import { getRuntimeModelStatus, probeRuntimeModel } from '../entities/model/api/modelApi'
import { createRuntimeModelStatus } from '../test/fixtures/modelControlPlaneTestFixtures'
import { getUsageByOrganizations, getUsageDaily, getOrganizationUsageDaily, parseUsageDailySummary, type UsageDailySummary } from '../features/usage/api/usageApi'
import { getPlatformBudgetSummary } from '../features/usage/api/platformBudgetApi'
import { parseUsageOrganizationSummary } from '../features/usage/api/usage/usageParsers'
import AdminDashboardPage from './AdminDashboardPage'

vi.mock('../entities/organization/api/organizationApi', () => ({getOrganizations: vi.fn()}))
vi.mock('../entities/user/api/userApi', () => ({getUserStatistics: vi.fn()}))
vi.mock('../entities/model/api/modelApi', () => ({getRuntimeModelStatus: vi.fn(), probeRuntimeModel: vi.fn()}))
vi.mock('../features/usage/api/usageApi', async original => ({
    ...await original<typeof import('../features/usage/api/usageApi')>(), getUsageDaily: vi.fn(), getUsageByOrganizations: vi.fn(), getOrganizationUsageDaily: vi.fn(),
}))
vi.mock('../features/usage/api/platformBudgetApi', () => ({getPlatformBudgetSummary: vi.fn()}))
vi.mock('../features/usage/ui/UsageDailyChart', () => ({
    UsageDailyChart: ({rows}: {rows: UsageDailySummary[]}) => <div>Дней в графике: {rows.length}</div>,
}))

const day = parseUsageDailySummary({
    usageDate: new Date().toISOString().slice(0, 10), aggregationZone: 'UTC',
    responses: {assistantMessages: 1},
    usage: {confirmedInputTokens: 3, confirmedOutputTokens: 5, availableUsageMessages: 1, partialUsageMessages: 0, missingUsageMessages: 0, usageNotApplicableMessages: 0},
    cost: {knownCostUsd: '0.000000000001', currency: 'USD', pricedMessages: 1, freeMessages: 0, unpricedMessages: 0, pricingFailedMessages: 0, pricingNotApplicableMessages: 0},
})
const org = parseUsageOrganizationSummary({
    organizationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', organizationName: 'Компания A',
    responses: {assistantMessages: 1},
    usage: {confirmedInputTokens: 3, confirmedOutputTokens: 5, availableUsageMessages: 1, partialUsageMessages: 0, missingUsageMessages: 0, usageNotApplicableMessages: 0},
    cost: {knownCostUsd: null, currency: 'USD', pricedMessages: 0, freeMessages: 0, unpricedMessages: 1, pricingFailedMessages: 0, pricingNotApplicableMessages: 0},
})

function open() { return render(<MemoryRouter><AdminDashboardPage /></MemoryRouter>) }

describe('PlatformDashboard', () => {
    beforeEach(() => {
        vi.resetAllMocks()
        vi.mocked(getOrganizations).mockResolvedValue({content: [], page: 0, size: 1, totalElements: 17, totalPages: 17})
        vi.mocked(getUserStatistics).mockResolvedValue({total: 42, administrators: 2, users: 40, enabled: 35, disabled: 7})
        vi.mocked(getRuntimeModelStatus).mockResolvedValue(createRuntimeModelStatus())
        vi.mocked(getUsageDaily).mockResolvedValue([day])
        vi.mocked(getOrganizationUsageDaily).mockResolvedValue([day])
        vi.mocked(getPlatformBudgetSummary).mockResolvedValue({
            periodStart: '2026-11-01T00:00:00Z', periodEnd: '2026-12-01T00:00:00Z', asOf: '2026-11-20T00:00:00Z',
            total: 17, enabled: 15, within: 1, nearLimit: 2, exceeded: 3, noLimit: 4, unknown: 1, disabled: 1, unconfigured: 5,
        })
        vi.mocked(getUsageByOrganizations).mockResolvedValue({content: [org], page: 0, size: 5, totalElements: 17, totalPages: 4})
    })
    it('uses global server totals and a bounded ranking, exact cost and explicit diagnostic link', async () => {
        open()
        expect(await within(screen.getByRole('region', {name: 'Организации'})).findByText('17')).toBeVisible()
        expect(await screen.findByText('42')).toBeVisible()
        expect(await screen.findByText('Дней в графике: 1')).toBeVisible()
        expect(screen.getByText('0.000000000001 USD')).toBeVisible()
        const link = await screen.findByRole('link', {name: 'Компания A'})
        expect(link.getAttribute('href')).toContain('organizationId=' + org.organizationId)
        expect(within(link.closest('tr')!).getByText(/Неполный учёт/)).toBeVisible()
        expect(getUsageByOrganizations).toHaveBeenCalledWith(0, 5, expect.objectContaining({order: 'total'}), expect.anything())
        expect(getOrganizations).toHaveBeenCalledWith(0, 1, expect.anything())
        expect(screen.getByRole('link', {name: 'Диагностика моделей'})).toHaveAttribute('href', '/admin/models?section=diagnostics')
        expect(probeRuntimeModel).not.toHaveBeenCalled()
    })
    it('isolates failed widgets, retries and ignores stale period responses', async () => {
        vi.mocked(getUserStatistics).mockRejectedValueOnce(new Error('DB error'))
            .mockResolvedValueOnce({total: 42, administrators: 2, users: 40, enabled: 35, disabled: 7})
        let resolveOld!: (rows: UsageDailySummary[]) => void
        vi.mocked(getUsageDaily).mockImplementationOnce(() => new Promise(resolve => {resolveOld = resolve}))
        open()
        const users = screen.getByRole('region', {name: 'Пользователи'})
        expect(await within(users).findByRole('alert')).toBeVisible()
        expect(await screen.findByText('17')).toBeVisible()
        fireEvent.click(within(users).getByRole('button', {name: 'Повторить'}))
        expect(await within(users).findByText('42')).toBeVisible()
        fireEvent.change(screen.getByLabelText('Период'), {target: {value: '7'}})
        expect(await screen.findByText('Дней в графике: 1')).toBeVisible()
        expect(vi.mocked(getUsageDaily).mock.calls[0][1]?.signal?.aborted).toBe(true)
        await act(async () => resolveOld([]))
        expect(screen.getByText('Дней в графике: 1')).toBeVisible()
        await waitFor(() => expect(getUsageByOrganizations).toHaveBeenCalledTimes(2))
        expect(getUserStatistics).toHaveBeenCalledTimes(2)
    })
    it('searches beyond the ranking with case preserved for server normalization and scopes only analytics', async () => {
        open()
        await screen.findByRole('link', {name: 'Компания A'})
        vi.mocked(getUsageByOrganizations).mockResolvedValue({content: [org], page: 0, size: 10, totalElements: 1, totalPages: 1})
        fireEvent.change(screen.getByRole('searchbox', {name: 'Поиск организаций'}), {target: {value: ' КОМ '}})
        await waitFor(() => expect(getUsageByOrganizations).toHaveBeenLastCalledWith(0, 10,
            expect.objectContaining({organizationQuery: 'КОМ', usedOnly: undefined}), expect.anything()))
        await screen.findByRole('link', {name: 'Компания A'})
        fireEvent.click(screen.getByRole('button', {name: 'Показать на графике'}))
        await waitFor(() => expect(getOrganizationUsageDaily).toHaveBeenCalledWith(org.organizationId, expect.anything(), expect.anything()))
        expect(screen.getByRole('link', {name: 'Полный отчёт'})).toHaveAttribute('href', expect.stringContaining(org.organizationId))
        expect(screen.getByRole('button', {name: 'Показать на графике'})).toHaveAttribute('aria-pressed', 'true')
        expect(screen.getByText('От 80% до лимита')).toBeVisible()
        fireEvent.click(screen.getByRole('button', {name: 'Все организации'}))
        await waitFor(() => expect(getUsageDaily).toHaveBeenCalledTimes(2))
    })
    it('offers all seven budget categories including zero and keeps them above analytics', async () => {
        vi.mocked(getPlatformBudgetSummary).mockResolvedValue({
            periodStart: '2026-11-01T00:00:00Z', periodEnd: '2026-12-01T00:00:00Z', asOf: '2026-11-20T00:00:00Z',
            total: 17, enabled: 17, within: 1, nearLimit: 0, exceeded: 0, noLimit: 2, unknown: 0, disabled: 0, unconfigured: 14,
        })
        open()
        const region = screen.getByRole('region', {name: 'Контроль бюджетов организаций'})
        await within(region).findByRole('link', {name: /Правила не заданы: 14/})
        for (const status of ['WITHIN', 'NEAR', 'EXCEEDED', 'NO_LIMIT', 'UNKNOWN', 'DISABLED', 'UNCONFIGURED']) {
            expect(region.querySelector('a[href*="budgetStatus=' + status + '"]')).toBeVisible()
        }
        expect(within(region).getByRole('link', {name: /Превысили лимит: 0/})).toBeEnabled()
        expect(region.compareDocumentPosition(screen.getByRole('region', {name: 'Период аналитики'})) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
        expect(await screen.findByText('Попытки ответа AI')).toBeVisible()
    })
    it('keeps the same results viewport and pager through debounce, pending, empty and error states', async () => {
        open()
        await screen.findByRole('link', {name: 'Компания A'})
        const region = screen.getByRole('region', {name: 'Организации по подтверждённым токенам'})
        const viewport = within(region).getByRole('region', {name: 'Результаты поиска организаций'})
        const pager = region.querySelector('.platform-dashboard__search-pager')
        const input = within(region).getByRole('searchbox')
        const expandedClass = 'platform-dashboard__panel--search-active'
        const sameLayout = () => {
            expect(within(region).getByRole('region', {name: 'Результаты поиска организаций'})).toBe(viewport)
            expect(region.querySelector('.platform-dashboard__search-pager')).toBe(pager)
        }
        expect(region).not.toHaveClass(expandedClass)
        expect(pager).toHaveAttribute('hidden')
        let resolveEmpty!: (value: Awaited<ReturnType<typeof getUsageByOrganizations>>) => void
        vi.mocked(getUsageByOrganizations).mockImplementationOnce(() => new Promise(resolve => {resolveEmpty = resolve}))
        fireEvent.change(input, {target: {value: 'нет'}})
        sameLayout()
        expect(region).toHaveClass(expandedClass)
        expect(pager).not.toHaveAttribute('hidden')
        expect(viewport).toHaveAttribute('aria-busy', 'true')
        expect(within(viewport).queryByRole('link', {name: 'Компания A'})).toBeNull()
        await waitFor(() => expect(getUsageByOrganizations).toHaveBeenCalledTimes(2))
        expect(within(region).getByRole('button', {name: 'Далее'})).toBeDisabled()
        await act(async () => resolveEmpty({content: [], page: 0, size: 10, totalElements: 0, totalPages: 0}))
        expect(await within(viewport).findByText('Организации не найдены.')).toBeVisible()
        sameLayout()
        expect(region).toHaveClass(expandedClass)
        vi.mocked(getUsageByOrganizations).mockRejectedValueOnce(new Error('test failure'))
        fireEvent.change(input, {target: {value: 'ошибка'}})
        expect(await within(viewport).findByRole('alert')).toBeVisible()
        sameLayout()
        expect(region).toHaveClass(expandedClass)
        expect(within(region).getByRole('button', {name: 'Далее'})).toBeDisabled()
        vi.mocked(getUsageByOrganizations).mockResolvedValueOnce({content: [org], page: 0, size: 10, totalElements: 1, totalPages: 1})
        fireEvent.click(within(viewport).getByRole('button', {name: 'Повторить'}))
        await within(viewport).findByRole('link', {name: 'Компания A'})
        sameLayout()
        let resolveRanking!: (value: Awaited<ReturnType<typeof getUsageByOrganizations>>) => void
        vi.mocked(getUsageByOrganizations).mockImplementationOnce(() => new Promise(resolve => {resolveRanking = resolve}))
        fireEvent.click(within(region).getByRole('button', {name: 'Сбросить'}))
        expect(region).toHaveClass(expandedClass)
        expect(viewport).toHaveAttribute('aria-busy', 'true')
        expect(pager).not.toHaveAttribute('hidden')
        await waitFor(() => expect(getUsageByOrganizations).toHaveBeenLastCalledWith(0, 5,
            expect.objectContaining({usedOnly: true}), expect.anything()))
        await act(async () => resolveRanking({content: [org], page: 0, size: 5, totalElements: 1, totalPages: 1}))
        await within(viewport).findByRole('link', {name: 'Компания A'})
        sameLayout()
        expect(region).not.toHaveClass(expandedClass)
        expect(pager).toHaveAttribute('hidden')
        // Whitespace does not start an expanded search session.
        fireEvent.change(input, {target: {value: '   '}})
        expect(region).not.toHaveClass(expandedClass)
        expect(pager).toHaveAttribute('hidden')
    })
})
