// ============================================================
// frontend/src/api/organizationApi.test.ts
// ============================================================
import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    disableOrganization,
    enableOrganization,
    getOrganizationDetails,
    isProtectedOrganization,
    normalizeOrganizationConfirmation,
    normalizeOrganizationName,
    parseOrganization,
    parseOrganizationDirectoryItem,
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

    it('отклоняет ответ без type', () => {
        const {
            type: _type,
            ...withoutType
        } = ORGANIZATION

        expect(() =>
            parseOrganization(
                withoutType,
            ),
        ).toThrow(
            'неизвестное значение',
        )
    })

    it('отклоняет неизвестный organization type', () => {
        expect(() =>
            parseOrganization({
                ...ORGANIZATION,
                type: 'INTERNAL',
            }),
        ).toThrow(
            'неизвестное значение',
        )
    })

    it('отклоняет PLATFORM без protected', () => {
        expect(() =>
            parseOrganization({
                ...ORGANIZATION,
                type: 'PLATFORM',
                protected: false,
            }),
        ).toThrow(
            'PLATFORM должна быть protected',
        )
    })

    it('отклоняет TENANT с protected=true', () => {
        expect(() =>
            parseOrganization({
                ...ORGANIZATION,
                protected: true,
            }),
        ).toThrow(
            'TENANT не может быть protected',
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


    it('confirmation игнорирует регистр, кавычки и лишние пробелы', () => {
        const expected =
            normalizeOrganizationConfirmation(
                'ООО "Зил"',
            )

        const accepted = [
            'ООО "Зил"',
            'ооо "зил"',
            'ООО «ЗИЛ»',
            "ООО 'Зил'",
            'ООО Зил',
            '  ООО   «Зил»  ',
            'ООО „Зил“',
            'ООО “Зил”',
        ]

        accepted.forEach((value) => {
            expect(
                normalizeOrganizationConfirmation(
                    value,
                ),
            ).toBe(expected)
        })
    })

    it('confirmation не допускает смысловые опечатки', () => {
        const expected =
            normalizeOrganizationConfirmation(
                'ООО "Зил"',
            )

        const rejected = [
            'Зил',
            'ООО Зел',
            'ООО Зилл',
            'ООО Зио',
            'АО Зил',
            'ООО З И Л',
            'ООО-Зил',
        ]

        rejected.forEach((value) => {
            expect(
                normalizeOrganizationConfirmation(
                    value,
                ),
            ).not.toBe(expected)
        })
    })

    it('directory parser не фабрикует createdAt', () => {
        const parsed =
            parseOrganizationDirectoryItem({
                id: ORGANIZATION.id,
                name: ORGANIZATION.name,
                enabled: true,
                type: 'TENANT',
                protected: false,
                version: 12,
            })

        expect(parsed.version).toBe(12)
        expect(parsed.type).toBe('TENANT')
    })

    it('PLATFORM считается защищённой', () => {
        expect(
            isProtectedOrganization({
                type: 'PLATFORM',
                protected: true,
            }),
        ).toBe(true)
    })

    it('TENANT считается изменяемой', () => {
        expect(
            isProtectedOrganization({
                type: 'TENANT',
                protected: false,
            }),
        ).toBe(false)
    })

    it('details использует отдельный GET endpoint', async () => {
        requestMock.mockResolvedValue(
            ORGANIZATION,
        )

        await getOrganizationDetails(
            ORGANIZATION.id,
        )

        expect(requestMock)
            .toHaveBeenCalledWith(
                `/api/organizations/${ORGANIZATION.id}`,
                expect.objectContaining({
                    method: 'GET',
                }),
            )
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

    it('disable передаёт введённое confirmationName без подмены', async () => {
        requestMock.mockResolvedValue({
            ...ORGANIZATION,
            enabled: false,
            version: 13,
        })

        const typedConfirmation =
            '  demo   company  '

        await disableOrganization(
            ORGANIZATION.id,
            {
                expectedVersion: 12,
                confirmationName:
                    typedConfirmation,
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
                            typedConfirmation,
                    },
                }),
            )
    })

    it('enable передаёт expectedVersion', async () => {
        requestMock.mockResolvedValue({
            ...ORGANIZATION,
            enabled: true,
            version: 14,
        })

        await enableOrganization(
            ORGANIZATION.id,
            {
                expectedVersion: 13,
            },
        )

        expect(requestMock)
            .toHaveBeenCalledWith(
                `/api/organizations/${ORGANIZATION.id}/enable`,
                expect.objectContaining({
                    method: 'POST',
                    json: {
                        expectedVersion: 13,
                    },
                }),
            )
    })
})
