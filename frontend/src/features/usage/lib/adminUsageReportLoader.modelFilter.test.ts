// frontend/src/features/usage/lib/adminUsageReportLoader.modelFilter.test.ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '../api/usageApi'
import { loadUsageReport } from './adminUsageReportLoader'
import type { PageResponse } from '../../../shared/lib/page'

vi.mock('../api/usageApi', () => ({
    getUsageSummary: vi.fn(), getUsageByOrganization: vi.fn(),
    getUsageByUsers: vi.fn(), getOrganizationUsageUsers: vi.fn(),
    getUsageByModels: vi.fn(), getOrganizationUsageModels: vi.fn(),
    getUsageDaily: vi.fn(), getOrganizationUsageDaily: vi.fn(),
}))
const ORG = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const FILTER = {dateFrom: '2026-10-01T00:00:00Z', dateTo: '2026-10-02T00:00:00Z', model: 'mock-safeai'}
const PAGE: PageResponse<never> = {content: [], page: 0, size: 50, totalElements: 0, totalPages: 0}

beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(api.getUsageSummary).mockResolvedValue(PAGE)
    vi.mocked(api.getUsageByOrganization).mockResolvedValue(PAGE)
    vi.mocked(api.getUsageByUsers).mockResolvedValue(PAGE)
    vi.mocked(api.getOrganizationUsageUsers).mockResolvedValue(PAGE)
    vi.mocked(api.getUsageByModels).mockResolvedValue([])
    vi.mocked(api.getOrganizationUsageModels).mockResolvedValue([])
    vi.mocked(api.getUsageDaily).mockResolvedValue([])
    vi.mocked(api.getOrganizationUsageDaily).mockResolvedValue([])
})

describe('usage aggregation filtering before paging', () => {
    it.each(['summary', 'users', 'models', 'daily'] as const)(
        'passes exact model to %s (tenant-scoped ADMIN)', async tab => {
            await loadUsageReport({tab, page: 0, filter: FILTER, organizationId: ORG,
                superAdmin: false, signal: new AbortController().signal})
            const expected = tab === 'summary' ? api.getUsageSummary
                : tab === 'users' ? api.getUsageByUsers
                : tab === 'models' ? api.getUsageByModels : api.getUsageDaily
            expect(expected).toHaveBeenCalledWith(...(tab === 'summary' || tab === 'users'
                ? [0, 50, FILTER, expect.any(Object)]
                : [FILTER, expect.any(Object)]))
            expect(api.getUsageByOrganization).not.toHaveBeenCalled()
            expect(api.getOrganizationUsageUsers).not.toHaveBeenCalled()
            expect(api.getOrganizationUsageModels).not.toHaveBeenCalled()
            expect(api.getOrganizationUsageDaily).not.toHaveBeenCalled()
        },
    )
    it.each(['summary', 'users', 'models', 'daily'] as const)(
        'passes exact model to %s (explicit SUPER_ADMIN organization)', async tab => {
            await loadUsageReport({tab, page: 0, filter: FILTER, organizationId: ORG,
                superAdmin: true, signal: new AbortController().signal})
            const expected = tab === 'summary' ? api.getUsageByOrganization
                : tab === 'users' ? api.getOrganizationUsageUsers
                : tab === 'models' ? api.getOrganizationUsageModels : api.getOrganizationUsageDaily
            expect(expected).toHaveBeenCalledWith(...(tab === 'summary' || tab === 'users'
                ? [ORG, 0, 50, FILTER, expect.any(Object)]
                : [ORG, FILTER, expect.any(Object)]))
        },
    )
})
