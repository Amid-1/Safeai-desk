import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    disableOrganization,
    isProtectedOrganization,
    normalizeOrganizationName,
    parseOrganization,
    updateOrganizationName,
} from './organizationApi'
import { apiRequest } from './http'

vi.mock('./http', async (importOriginal) => {
    const actual =
        await importOriginal<
            typeof import('./http')
        >()

    return {
        ...actual,
        apiRequest: vi.fn(),
    }
})

const requestMock =
    vi.mocked(apiRequest)

const ORGANIZATION = {
    id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    name: 'Demo Company',
    enabled: true,
    type: 'TENANT',
    protected: false,
    version: 12,
    createdAt:
        '2026-08-04T10:00:00Z',
    updatedAt:
        '2026-08-04T10:00:01Z',
}

describe('organizationApi', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('неизвестный protection contract считается защищённым', () => {
        const parsed =
            parseOrganization({
                id: ORGANIZATION.id,
                name: ORGANIZATION.name,
                enabled: true,
                createdAt:
                    ORGANIZATION.createdAt,
            })

        expect(parsed.type).toBe('UNKNOWN')
        expect(parsed.protected).toBeNull()
        expect(
            isProtectedOrganization(
                parsed,
            ),
        ).toBe(true)
    })

    it('неизвестный organization type отклоняется', () => {
        expect(() =>
            parseOrganization({
                ...ORGANIZATION,
                type: 'INTERNAL',
            }),
        ).toThrow(
            'неизвестное значение',
        )
    })

    it('отрицательная version отклоняется', () => {
        expect(() =>
            parseOrganization({
                ...ORGANIZATION,
                version: -1,
            }),
        ).toThrow(
            'неотрицательным',
        )
    })

    it('нормализует пробелы имени', () => {
        expect(
            normalizeOrganizationName(
                '  Demo   Company  ',
            ),
        ).toBe('Demo Company')
    })

    it('rename передаёт expectedVersion', async () => {
        requestMock.mockResolvedValue(
            ORGANIZATION,
        )

        await updateOrganizationName(
            ORGANIZATION.id,
            {
                name: 'New Name',
                expectedVersion: 12,
            },
        )

        expect(requestMock)
            .toHaveBeenCalledWith(
                `/api/organizations/${ORGANIZATION.id}`,
                expect.objectContaining({
                    method: 'PATCH',
                    json: {
                        name: 'New Name',
                        expectedVersion: 12,
                    },
                }),
            )
    })

    it('disable требует typed confirmation и expectedVersion', async () => {
        requestMock.mockResolvedValue({
            ...ORGANIZATION,
            enabled: false,
            version: 13,
        })

        await disableOrganization(
            ORGANIZATION.id,
            {
                expectedVersion: 12,
                confirmationName:
                    ORGANIZATION.name,
            },
        )

        expect(requestMock)
            .toHaveBeenCalledWith(
                `/api/organizations/${ORGANIZATION.id}/disable`,
                expect.objectContaining({
                    method: 'POST',
                    json: {
                        expectedVersion: 12,
                        confirmationName:
                            ORGANIZATION.name,
                    },
                }),
            )
    })
})
