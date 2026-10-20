import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './http'
import { getUsageSummary, getUsageByUsers, getUsageByModels, getUsageDaily,
    getOrganizationUsageUsers, getOrganizationUsageModels, getOrganizationUsageDaily } from './usageApi'

vi.mock('./http', async (importOriginal) => {
    const actual = await importOriginal<typeof import('./http')>()
    return {...actual, apiRequest: vi.fn()}
})
const org = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const filter = {dateFrom: '2026-10-01T00:00:00Z', dateTo: '2026-10-20T00:00:00Z', model: 'mock-safeai'}

beforeEach(() => { vi.clearAllMocks() })

describe('server-side usage model filter for every report', () => {
    it('sends exact model in seven report variants', async () => {
        vi.mocked(apiRequest).mockResolvedValue({content: [], page: 0, size: 50, first: true, last: true, hasNext: false, hasPrevious: false})
        await getUsageSummary(0, 50, filter)
        await getUsageByUsers(0, 50, filter)
        await getOrganizationUsageUsers(org, 0, 50, filter)
        vi.mocked(apiRequest).mockResolvedValue([])
        await getUsageByModels(filter)
        await getUsageDaily(filter)
        await getOrganizationUsageModels(org, filter)
        await getOrganizationUsageDaily(org, filter)
        expect(vi.mocked(apiRequest).mock.calls).toHaveLength(7)
        for (const [path] of vi.mocked(apiRequest).mock.calls) {
            const url = new URL(String(path), 'http://localhost')
            expect(url.searchParams.get('model')).toBe('mock-safeai')
        }
    })
})
