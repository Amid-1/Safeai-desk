import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    getKnowledgeBases,
    parseKnowledgeBase,
    removeKnowledgeBaseMember,
    updateKnowledgeBase,
} from './knowledgeApi'
import {
    apiRequest,
} from './http'

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

const KB = {
    id:
        '11111111-1111-4111-8111-111111111111',
    organizationId:
        'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    name: 'IT Runbooks',
    description: null,
    visibility: 'MEMBERS',
    enabled: true,
    createdByUserId:
        '22222222-2222-4222-8222-222222222222',
    version: 3,
    createdAt:
        '2026-08-13T10:00:00Z',
    updatedAt:
        '2026-08-13T10:01:00Z',
}

describe('knowledgeApi', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('rejects unknown visibility', () => {
        expect(() =>
            parseKnowledgeBase({
                ...KB,
                visibility: 'PUBLIC',
            }),
        ).toThrow(
            'неизвестное значение',
        )
    })

    it('parses PageResponse metadata', async () => {
        requestMock.mockResolvedValue({
            content: [KB],
            page: 1,
            size: 50,
            totalElements: 51,
            totalPages: 2,
        })

        const result =
            await getKnowledgeBases(
                1,
                50,
            )

        expect(result.page).toBe(1)
        expect(result.totalPages).toBe(2)

        expect(requestMock)
            .toHaveBeenCalledWith(
                expect.stringContaining(
                    'page=1',
                ),
                expect.any(Object),
            )
    })

    it('update sends expectedVersion', async () => {
        requestMock.mockResolvedValue({
            ...KB,
            version: 4,
        })

        await updateKnowledgeBase(
            KB.id,
            {
                name: 'IT Runbooks',
                description: null,
                visibility: 'MEMBERS',
                enabled: true,
                expectedVersion: 3,
            },
        )

        expect(requestMock)
            .toHaveBeenCalledWith(
                `/api/knowledge-bases/${KB.id}`,
                expect.objectContaining({
                    method: 'PATCH',
                    json: expect.objectContaining({
                        expectedVersion: 3,
                    }),
                }),
            )
    })

    it('member delete carries expectedVersion', async () => {
        requestMock.mockResolvedValue(
            undefined,
        )

        await removeKnowledgeBaseMember(
            KB.id,
            '33333333-3333-4333-8333-333333333333',
            7,
        )

        expect(requestMock)
            .toHaveBeenCalledWith(
                expect.stringContaining(
                    'expectedVersion=7',
                ),
                expect.objectContaining({
                    method: 'DELETE',
                }),
            )
    })
})
