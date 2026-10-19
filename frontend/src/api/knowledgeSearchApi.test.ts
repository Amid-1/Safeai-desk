import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './http'
import { searchKnowledgeBases } from './knowledgeApi'

vi.mock('./http', async (importOriginal) => {
    const actual = await importOriginal<typeof import('./http')>()
    return { ...actual, apiRequest: vi.fn() }
})

const requestMock = vi.mocked(apiRequest)

describe('searchKnowledgeBases', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        requestMock.mockResolvedValue({
            content: [], page: 1, size: 12,
            totalElements: 0, totalPages: 0,
        })
    })

    it('sends server-side search with explicit filters and bounded page size', async () => {
        await searchKnowledgeBases(1, 12, {
            query: ' База & документы ',
            enabled: false,
            visibility: 'MEMBERS',
        })

        expect(requestMock).toHaveBeenCalledWith(
            expect.stringContaining('/api/knowledge-bases/search?'),
            expect.objectContaining({ method: 'GET' }),
        )
        const requestPath = requestMock.mock.calls[0][0]
        const params = new URLSearchParams(requestPath.split('?')[1])
        expect(params.get('query')).toBe('База & документы')
        expect(params.get('enabled')).toBe('false')
        expect(params.get('visibility')).toBe('MEMBERS')
        expect(params.get('page')).toBe('1')
        expect(params.get('size')).toBe('12')
    })
})
