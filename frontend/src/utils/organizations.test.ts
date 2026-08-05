// frontend/src/utils/organizations.test.ts
import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'

import {
    loadAllOrganizations,
} from './organizations'

import {
    getOrganizations,
} from '../api/organizationApi'

vi.mock('../api/organizationApi', () => ({
    getOrganizations: vi.fn(),
}))

const getOrganizationsMock =
    vi.mocked(getOrganizations)

function page(
    currentPage: number,
    totalPages: number,
) {
    return {
        content: [
            {
                id:
                    `00000000-0000-0000-0000-${String(
                        currentPage + 1,
                    ).padStart(12, '0')}`,

                name:
                    `Organization ${currentPage}`,

                enabled: true,

                type:
                    'TENANT' as const,

                protected: false,

                version: 1,

                createdAt:
                    '2026-08-04T10:00:00Z',

                updatedAt:
                    '2026-08-04T10:00:00Z',
            },
        ],

        page: currentPage,
        size: 200,
        totalElements: totalPages,
        totalPages,
    }
}

describe('loadAllOrganizations', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('останавливается по maxPages', async () => {
        getOrganizationsMock
            .mockImplementation(
                async (
                    pageNumber = 0,
                ) =>
                    page(
                        pageNumber,
                        100,
                    ),
            )

        await expect(
            loadAllOrganizations({
                maxPages: 2,
            }),
        ).rejects.toMatchObject({
            errorCode:
                'DIRECTORY_LIMIT_EXCEEDED',
        })

        expect(
            getOrganizationsMock,
        ).toHaveBeenCalledTimes(2)
    })

    it('передаёт AbortSignal и прекращает загрузку', async () => {
        const controller =
            new AbortController()

        controller.abort()

        await expect(
            loadAllOrganizations({
                signal:
                    controller.signal,
            }),
        ).rejects.toMatchObject({
            errorCode:
                'REQUEST_ABORTED',
        })

        expect(
            getOrganizationsMock,
        ).not.toHaveBeenCalled()
    })
})