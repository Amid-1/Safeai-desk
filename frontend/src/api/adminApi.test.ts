// ============================================================
// frontend/src/api/audminApi.test.ts
// ============================================================
import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    getUsageByModels,
    getUsageByOrganization,
    getUsageByUser,
    getUsageByUsers,
    getUsageDaily,
    getUsageSummary,
    parseAuditDetails,
    parseAuditEvent,
} from './adminApi'
import {
    apiRequest,
} from './http'

vi.mock('./http', async (
    importOriginal,
) => {
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

const COVERAGE = {
    assistantMessageCount: 1,

    availableUsageMessageCount: 1,
    partialUsageMessageCount: 0,
    missingUsageMessageCount: 0,
    usageNotApplicableMessageCount: 0,

    pricedMessageCount: 1,
    freeMessageCount: 0,
    unpricedMessageCount: 0,
    pricingFailedMessageCount: 0,
    pricingNotApplicableMessageCount: 0,

    ambiguousProviderOperationCount: 0,
}

const SUMMARY_ROW = {
    userId:
        '11111111-1111-1111-1111-111111111111',
    userEmail:
        'user@safeai.test',
    model: 'mock-safeai',
    inputTokens: 10,
    outputTokens: 5,
    totalTokens: 15,
    costUsd: '0.001000000000',
    ...COVERAGE,
}

const PAGE = {
    content: [SUMMARY_ROW],
    number: 1,
    size: 50,
    totalElements: 75,
    totalPages: 2,
}

describe('adminApi usage contracts', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('/models принимает обычный array response', async () => {
        requestMock.mockResolvedValue([
            {
                model: 'mock-safeai',
                inputTokens: 10,
                outputTokens: 5,
                totalTokens: 15,
                costUsd: '0',
                ...COVERAGE,
            },
        ])

        const result =
            await getUsageByModels()

        expect(result).toHaveLength(1)
        expect(result[0]?.model)
            .toBe('mock-safeai')
    })

    it('/daily принимает обычный array response', async () => {
        requestMock.mockResolvedValue([
            {
                usageDate: '2026-08-04',
                inputTokens: 10,
                outputTokens: 5,
                totalTokens: 15,
                costUsd: '0',
                ...COVERAGE,
            },
        ])

        const result =
            await getUsageDaily()

        expect(result[0]?.usageDate)
            .toBe('2026-08-04')
    })

    it('/summary сохраняет pagination metadata и вторую страницу', async () => {
        requestMock.mockResolvedValue(
            PAGE,
        )

        const result =
            await getUsageSummary(
                1,
                50,
            )

        expect(result).toMatchObject({
            page: 1,
            size: 50,
            totalElements: 75,
            totalPages: 2,
        })

        expect(requestMock)
            .toHaveBeenCalledWith(
                expect.stringContaining(
                    'page=1',
                ),
                expect.any(Object),
            )
    })

    it('/users сохраняет pagination metadata', async () => {
        requestMock.mockResolvedValue({
            ...PAGE,
            content: [
                {
                    userId:
                        SUMMARY_ROW.userId,
                    userEmail:
                        SUMMARY_ROW.userEmail,
                    inputTokens: 10,
                    outputTokens: 5,
                    totalTokens: 15,
                    costUsd: '0',
                    ...COVERAGE,
                },
            ],
        })

        const result =
            await getUsageByUsers(
                1,
                50,
            )

        expect(result.totalElements)
            .toBe(75)
    })

    it('organization endpoint использует /organizations/{id}', async () => {
        requestMock.mockResolvedValue(
            PAGE,
        )

        await getUsageByOrganization(
            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            0,
            50,
        )

        expect(requestMock)
            .toHaveBeenCalledWith(
                expect.stringContaining(
                    '/api/admin/usage/by-organization/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa?',
                ),
                expect.any(Object),
            )
    })

    it('user endpoint использует /users/{id}', async () => {
        requestMock.mockResolvedValue(
            PAGE,
        )

        await getUsageByUser(
            SUMMARY_ROW.userId,
            0,
            50,
        )

        expect(requestMock)
            .toHaveBeenCalledWith(
                expect.stringContaining(
                    `/api/admin/usage/by-user/${SUMMARY_ROW.userId}?`,
                ),
                expect.any(Object),
            )
    })

    it('невалидный PageResponse отклоняется', async () => {
        requestMock.mockResolvedValue({
            content: null,
            number: 0,
            size: 50,
            totalElements: 0,
            totalPages: 0,
        })

        await expect(
            getUsageSummary(
                0,
                50,
            ),
        ).rejects.toMatchObject({
            errorCode:
                'INVALID_RESPONSE',
        })
    })
})

describe('audit runtime safety', () => {
    it('details=null не ломает parser', () => {
        const parsed =
            parseAuditDetails(null)

        expect(parsed.details)
            .toEqual({})
        expect(parsed.invalid)
            .toBe(false)
    })

    it('details=array не ломает parser', () => {
        const parsed =
            parseAuditDetails([
                'unexpected',
            ])

        expect(parsed.details)
            .toEqual({})
        expect(parsed.invalid)
            .toBe(true)
    })

    it('секретные ключи маскируются defense-in-depth', () => {
        const parsed =
            parseAuditDetails({
                Authorization:
                    'Bearer secret',
                safeField: 'visible',
            })

        expect(parsed.details)
            .toEqual({
                Authorization:
                    '[REDACTED]',
                safeField: 'visible',
            })
    })

    it('userId без snapshot имени не становится системой', () => {
        const event =
            parseAuditEvent({
                id:
                    '22222222-2222-2222-2222-222222222222',
                organizationId:
                    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
                userId:
                    SUMMARY_ROW.userId,
                userEmail: null,
                userDisplayName: null,
                eventType:
                    'USER_UPDATED',
                details: {},
                createdAt:
                    '2026-08-04T10:00:00Z',
            })

        expect(event.actorUserId)
            .toBe(SUMMARY_ROW.userId)
        expect(event.actorEmail)
            .toBeNull()
    })
})
