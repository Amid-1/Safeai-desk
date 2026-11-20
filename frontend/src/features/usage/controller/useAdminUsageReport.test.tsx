// frontend/src/features/usage/controller/useAdminUsageReport.test.tsx
import { act, renderHook } from '@testing-library/react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom'
import type { PropsWithChildren } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAdminUsageReport } from './useAdminUsageReport'
import { useAdminUsageReportQuery } from './useAdminUsageReportQuery'
import { useAdminUsageModelOptions } from './useAdminUsageModelOptions'
import { useAdminUsageOrganizationOptions } from './useAdminUsageOrganizationOptions'
import { parseUsageOrganizationSummary } from '../api/usage/usageParsers'

const org = '12345678-1234-1234-1234-123456789012'
vi.mock('../../auth/state/useAuth', () => ({
    useAuth: () => ({currentUser: {id: 'super', organizationId: '', roles: ['SUPER_ADMIN']}}),
}))
vi.mock('./useAdminUsageModelOptions')
vi.mock('./useAdminUsageOrganizationOptions')
vi.mock('./useAdminUsageReportQuery', () => ({
    useAdminUsageReportQuery: vi.fn(() => ({
        rows: [], hasNext: false, hasPrevious: false, loading: false, error: '',
    })),
}))

function Wrapper({children}: PropsWithChildren) {
    return <MemoryRouter initialEntries={[
        `/admin/usage?report=models&dateFrom=2026-11-01&dateTo=2026-11-16&organizationId=${org}`,
    ]}>{children}</MemoryRouter>
}

describe('Usage report drilldown', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        vi.mocked(useAdminUsageModelOptions).mockReturnValue({
            options: [], loading: false, error: '',
        })
        vi.mocked(useAdminUsageOrganizationOptions).mockReturnValue({
            query: '', options: [], loading: false, error: '', setQuery: vi.fn(),
            appliedOrganizationName: 'Organization A', draftOrganizationName: 'Organization A',
        })
    })

    it('preserves a budget filter across page and sort, clears it on other tabs and suppresses global totals', () => {
        const wrapper = ({children}: PropsWithChildren) => <MemoryRouter initialEntries={['/admin/usage?report=organizations&budgetStatus=NEAR']}>{children}</MemoryRouter>
        const {result} = renderHook(useAdminUsageReport, {wrapper})
        expect(result.current.budgetStatus).toBe('NEAR')
        expect(useAdminUsageReportQuery).toHaveBeenLastCalledWith(expect.objectContaining({tab: 'daily', enabled: false}))
        act(() => result.current.goToPage(2))
        expect(result.current.page).toBe(2)
        expect(result.current.budgetStatus).toBe('NEAR')
        act(() => result.current.changeOrder('cost'))
        expect(result.current.page).toBe(0)
        expect(result.current.budgetStatus).toBe('NEAR')
        expect(useAdminUsageReportQuery).toHaveBeenCalledWith(expect.objectContaining({tab: 'organizations', order: 'cost', budgetStatus: 'NEAR'}))
        act(() => result.current.clearBudgetStatus())
        expect(result.current.budgetStatus).toBeUndefined()
        expect(useAdminUsageReportQuery).toHaveBeenLastCalledWith(expect.objectContaining({tab: 'daily', enabled: true}))
        act(() => result.current.selectTab('users'))
        expect(result.current.budgetStatus).toBeUndefined()
    })

    it('does not navigate or change period for an organization without responses', () => {
        const empty = parseUsageOrganizationSummary({organizationId: org, organizationName: 'Organization A',
            responses: {assistantMessages: 0}, usage: {confirmedInputTokens: 0, confirmedOutputTokens: 0,
                availableUsageMessages: 0, partialUsageMessages: 0, missingUsageMessages: 0, usageNotApplicableMessages: 0},
            cost: {knownCostUsd: null, currency: 'USD', pricedMessages: 0, freeMessages: 0, unpricedMessages: 0,
                pricingFailedMessages: 0, pricingNotApplicableMessages: 0}})
        const {result} = renderHook(useAdminUsageReport, {wrapper: Wrapper})
        act(() => result.current.inspectRow(empty))
        expect(result.current.tab).toBe('models')
        expect(result.current.appliedOrganizationId).toBe(org)
        expect(result.current.appliedDateFrom).toBe('2026-11-01')
        expect(result.current.appliedDateTo).toBe('2026-11-16')
        act(() => result.current.inspectRow({...empty, coverage: {...empty.coverage, assistantMessages: '1'}}))
        expect(result.current.tab).toBe('summary')
    })

    it('keeps tenant, period and model scope when drilling into summary', () => {
        const {result} = renderHook(useAdminUsageReport, {wrapper: Wrapper})
        act(() => result.current.inspectModel('mock-safeai'))
        expect(result.current.tab).toBe('summary')
        expect(result.current.appliedModel).toBe('mock-safeai')
        expect(result.current.page).toBe(0)
        expect(vi.mocked(useAdminUsageReportQuery).mock.calls.at(-2)?.[0]).toEqual(expect.objectContaining({
            tab: 'summary', page: 0, organizationId: org, model: 'mock-safeai',
            dateFrom: '2026-11-01', dateTo: '2026-11-16',
        }))
        act(() => result.current.inspectDay('2026-11-15'))
        expect(vi.mocked(useAdminUsageReportQuery).mock.calls.at(-2)?.[0]).toEqual(expect.objectContaining({
            tab: 'summary', page: 0, organizationId: org, model: 'mock-safeai',
            dateFrom: '2026-11-15', dateTo: '2026-11-15',
        }))
        expect(vi.mocked(useAdminUsageReportQuery)).toHaveBeenLastCalledWith(expect.objectContaining({
            enabled: true, tab: 'daily', page: 0, organizationId: org, model: 'mock-safeai',
            dateFrom: '2026-11-15', dateTo: '2026-11-15',
        }))
        expect(useAdminUsageModelOptions).toHaveBeenLastCalledWith({
            enabled: true, isSuperAdmin: true, organizationId: org,
            dateFrom: '2026-11-15', dateTo: '2026-11-15',
        })
        expect(useAdminUsageOrganizationOptions).toHaveBeenLastCalledWith({
            enabled: true, draftOrganizationId: org, appliedOrganizationId: org,
        })
    })

    it('keeps user, model, and tenant in every report and clears filters independently', () => {
        const userId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
        const {result} = renderHook(useAdminUsageReport, {wrapper: Wrapper})
        act(() => result.current.inspectModel('mock-safeai'))
        act(() => result.current.inspectUser(userId, 'admin@example.test'))
        expect(result.current.appliedUserLabel).toBe('admin@example.test')
        act(() => result.current.selectTab('daily'))
        expect(vi.mocked(useAdminUsageReportQuery).mock.calls.at(-2)?.[0]).toEqual(expect.objectContaining({
            tab: 'daily', filterUserId: userId, organizationId: org, model: 'mock-safeai',
        }))
        act(() => result.current.inspectDay('2026-11-15'))
        expect(result.current.appliedUserId).toBe(userId)
        act(() => result.current.clearFilter('model'))
        expect(vi.mocked(useAdminUsageReportQuery).mock.calls.at(-2)?.[0]).toEqual(expect.objectContaining({
            tab: 'summary', filterUserId: userId, organizationId: org, model: '',
            dateFrom: '2026-11-15', dateTo: '2026-11-15',
        }))
        act(() => result.current.clearFilter('user'))
        expect(result.current.appliedUserId).toBe('')
        expect(result.current.appliedDateFrom).toBe('2026-11-15')
    })

    it('keeps the chosen model and user when editing the period', () => {
        const {result} = renderHook(useAdminUsageReport, {wrapper: Wrapper})
        act(() => result.current.inspectModel('mock-safeai'))
        act(() => result.current.inspectUser('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'admin@example.test'))
        act(() => result.current.changeDraftDateFrom('2026-11-10'))
        act(() => result.current.applyFilters())
        expect(result.current.appliedModel).toBe('mock-safeai')
        expect(result.current.appliedUserId).toBe('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
        expect(result.current.appliedDateFrom).toBe('2026-11-10')
    })

    it('does not silently overwrite draft filters', () => {
        const {result} = renderHook(useAdminUsageReport, {wrapper: Wrapper})
        act(() => result.current.changeDraftDateFrom('2026-11-10'))
        act(() => result.current.inspectModel('mock-safeai'))
        expect(result.current.tab).toBe('models')
        expect(result.current.filtersDirty).toBe(true)
        expect(result.current.draftDateFrom).toBe('2026-11-10')
    })

    it('restores comparison, scope, sorting and page on Back and Forward after drilldown', () => {
        const {result} = renderHook(() => ({report: useAdminUsageReport(), location: useLocation(), navigate: useNavigate()}), {wrapper: Wrapper})
        act(() => result.current.report.selectTab('organizations'))
        act(() => result.current.report.selectTab('users'))
        act(() => result.current.report.selectTab('organizations'))
        act(() => result.current.report.changeOrder('cost'))
        act(() => result.current.report.goToPage(2))
        const comparison = result.current.location.search
        expect(comparison).toContain('report=organizations')
        expect(comparison).not.toContain('organizationId=')
        const row = parseUsageOrganizationSummary({organizationId: org, organizationName: 'Organization A',
            responses: {assistantMessages: 1}, usage: {confirmedInputTokens: 9, confirmedOutputTokens: 16,
                availableUsageMessages: 1, partialUsageMessages: 0, missingUsageMessages: 0, usageNotApplicableMessages: 0},
            cost: {knownCostUsd: '0', currency: 'USD', pricedMessages: 1, freeMessages: 0,
                unpricedMessages: 0, pricingFailedMessages: 0, pricingNotApplicableMessages: 0}})
        act(() => result.current.report.inspectRow(row))
        const detail = result.current.location.search
        expect(detail).toContain(`organizationId=${org}`)
        expect(result.current.report.tab).toBe('summary')
        vi.mocked(useAdminUsageReportQuery).mockClear()
        act(() => { void result.current.navigate(-1) })
        expect(result.current.location.search).toBe(comparison)
        expect(result.current.report).toMatchObject({tab: 'organizations', appliedOrganizationId: '',
            page: 2, order: 'cost', appliedDateFrom: '2026-11-01', appliedDateTo: '2026-11-16', filtersDirty: false})
        expect(vi.mocked(useAdminUsageReportQuery).mock.calls.at(-2)?.[0]).toEqual(expect.objectContaining({
            enabled: true, tab: 'organizations', organizationId: '', page: 2, order: 'cost',
        }))
        // No render after POP is allowed to load the previous tenant's report.
        expect(vi.mocked(useAdminUsageReportQuery).mock.calls.every(([query]) => query.organizationId === '')).toBe(true)
        act(() => { void result.current.navigate(1) })
        expect(result.current.location.search).toBe(detail)
        expect(result.current.report).toMatchObject({tab: 'summary', appliedOrganizationId: org, page: 0, order: 'cost', filtersDirty: false})
    })

    it('preserves unapplied edits across tab switches and resets them on browser Back', () => {
        const {result} = renderHook(() => ({report: useAdminUsageReport(), navigate: useNavigate()}), {wrapper: Wrapper})
        act(() => result.current.report.changeDraftDateFrom('2026-11-10'))
        act(() => result.current.report.selectTab('users'))
        expect(result.current.report).toMatchObject({tab: 'users', draftDateFrom: '2026-11-10',
            appliedDateFrom: '2026-11-01', filtersDirty: true})
        act(() => { void result.current.navigate(-1) })
        expect(result.current.report).toMatchObject({tab: 'models', draftDateFrom: '2026-11-01',
            appliedDateFrom: '2026-11-01', filtersDirty: false})
    })
})
