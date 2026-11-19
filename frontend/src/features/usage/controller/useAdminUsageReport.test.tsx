// frontend/src/features/usage/controller/useAdminUsageReport.test.tsx
import { act, renderHook } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { PropsWithChildren } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAdminUsageReport } from './useAdminUsageReport'
import { useAdminUsageReportQuery } from './useAdminUsageReportQuery'
import { useAdminUsageModelOptions } from './useAdminUsageModelOptions'
import { useAdminUsageOrganizationOptions } from './useAdminUsageOrganizationOptions'

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
})
