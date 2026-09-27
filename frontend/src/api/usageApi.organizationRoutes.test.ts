// ============================================================
// frontend/src/api/usageApi.organizationRoutes.test.ts
// ============================================================

import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    apiRequest,
} from './http'
import {
    getOrganizationUsageDaily,
    getOrganizationUsageModels,
    getOrganizationUsageUsers,
    getUsageByOrganization,
} from './usageApi'

vi.mock('./http', () => ({
    API_TIMEOUTS: {
        report: 30_000,
    },
    apiRequest: vi.fn(),
}))

const ORGANIZATION_ID =
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

const EMPTY_PAGE = {
    content: [],
    page: 0,
    size: 50,
    first: true,
    last: true,
    hasNext: false,
    hasPrevious: false,
}

describe('usageApi organization routes', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('использует backend /by-organization route для детальной сводки', async () => {
        vi.mocked(apiRequest).mockResolvedValue(
            EMPTY_PAGE,
        )

        await getUsageByOrganization(
            ORGANIZATION_ID,
        )

        expect(apiRequest).toHaveBeenCalledWith(
            expect.stringContaining(
                `/api/admin/usage/by-organization/${ORGANIZATION_ID}`,
            ),
            expect.objectContaining({
                method: 'GET',
            }),
        )
    })

    it('использует backend /by-organization route для пользователей', async () => {
        vi.mocked(apiRequest).mockResolvedValue(
            EMPTY_PAGE,
        )

        await getOrganizationUsageUsers(
            ORGANIZATION_ID,
        )

        expect(apiRequest).toHaveBeenCalledWith(
            expect.stringContaining(
                `/api/admin/usage/by-organization/${ORGANIZATION_ID}/users`,
            ),
            expect.objectContaining({
                method: 'GET',
            }),
        )
    })

    it('использует backend /by-organization route для моделей', async () => {
        vi.mocked(apiRequest).mockResolvedValue([])

        await getOrganizationUsageModels(
            ORGANIZATION_ID,
        )

        expect(apiRequest).toHaveBeenCalledWith(
            expect.stringContaining(
                `/api/admin/usage/by-organization/${ORGANIZATION_ID}/models`,
            ),
            expect.objectContaining({
                method: 'GET',
            }),
        )
    })

    it('использует backend /by-organization route для дневного отчёта', async () => {
        vi.mocked(apiRequest).mockResolvedValue([])

        await getOrganizationUsageDaily(
            ORGANIZATION_ID,
        )

        expect(apiRequest).toHaveBeenCalledWith(
            expect.stringContaining(
                `/api/admin/usage/by-organization/${ORGANIZATION_ID}/daily`,
            ),
            expect.objectContaining({
                method: 'GET',
            }),
        )
    })
})
