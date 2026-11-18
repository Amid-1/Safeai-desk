// frontend/src/entities/organization/api/organizationApi.directory.test.ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../../../shared/api/http'
import {
    getOrganizations,
    searchOrganizationDirectory,
} from './organizationApi'

vi.mock('../../../shared/api/http', async (importOriginal) => ({
    ...await importOriginal<typeof import('../../../shared/api/http')>(),
    apiRequest: vi.fn(),
}))

const requestMock = vi.mocked(apiRequest)
const TENANT = {
    id: '11111111-1111-4111-8111-111111111111',
    name: 'Компания',
    type: 'TENANT',
    enabled: false,
    protected: false,
    version: 2,
}
const PLATFORM = {
    ...TENANT,
    id: '00000000-0000-0000-0000-000000000001',
    name: 'SafeAI Platform',
    type: 'PLATFORM',
    enabled: true,
    protected: true,
}

function lastRequestUrl(): URL {
    const [path] = requestMock.mock.lastCall ?? []
    expect(path).toEqual(expect.any(String))
    return new URL(String(path), 'https://safeai.invalid')
}

describe('organization directory API: server filters and strict responses', () => {
    beforeEach(() => { vi.resetAllMocks() })

    it('uses backend search before paging and preserves explicit enabled=false', async () => {
        requestMock.mockResolvedValue({
            content: [{ ...TENANT, createdAt: '2026-11-01T10:00:00Z', updatedAt: '2026-11-01T11:00:00Z' }],
            page: 2,
            size: 100,
            totalElements: 201,
            totalPages: 3,
        })
        const signal = new AbortController().signal
        const result = await getOrganizations(2, 500, {
            signal, query: '%_!', enabled: false, type: 'TENANT',
        })
        const url = lastRequestUrl()
        expect(url.pathname).toBe('/api/organizations/search')
        expect(url.searchParams.get('query')).toBe('%_!')
        expect(url.searchParams.get('enabled')).toBe('false')
        expect(url.searchParams.get('type')).toBe('TENANT')
        expect(url.searchParams.get('page')).toBe('2')
        expect(url.searchParams.get('size')).toBe('100')
        expect(requestMock.mock.lastCall?.[1]).toEqual(expect.objectContaining({
            method: 'GET', signal,
        }))
        expect(result.totalElements).toBe(201)
        expect(result.content[0].enabled).toBe(false)
    })

    it('retains the ordinary paginated endpoint without filters', async () => {
        requestMock.mockResolvedValue({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
        const result = await getOrganizations()
        const url = lastRequestUrl()
        expect(url.pathname).toBe('/api/organizations')
        expect(url.searchParams.get('size')).toBe('50')
        expect(result.content).toHaveLength(0)
    })

    it('trims the directory search and includes both protected and disabled organizations', async () => {
        requestMock.mockResolvedValue([PLATFORM, TENANT])
        const signal = new AbortController().signal
        const items = await searchOrganizationDirectory('  SafeAI  ', 999, { signal })
        const url = lastRequestUrl()
        expect(url.pathname).toBe('/api/organizations/directory')
        expect(url.searchParams.get('query')).toBe('SafeAI')
        expect(url.searchParams.get('limit')).toBe('50')
        expect(requestMock.mock.lastCall?.[1]).toEqual(expect.objectContaining({ signal }))
        expect(items).toHaveLength(2)
        expect(items[0]).toMatchObject({ type: 'PLATFORM', protected: true })
        expect(items[1]).toMatchObject({ type: 'TENANT', enabled: false })
    })

    it('fails closed when a directory response is not an array', async () => {
        requestMock.mockResolvedValue({ content: [TENANT] })
        await expect(searchOrganizationDirectory('', 20)).rejects.toThrow('должен быть массивом')
    })

    it('fails closed when an item violates the organization type/protection contract', async () => {
        requestMock.mockResolvedValue([{ ...PLATFORM, protected: false }])
        await expect(searchOrganizationDirectory('SafeAI')).rejects.toThrow('PLATFORM должна быть protected')
        requestMock.mockResolvedValue([{ ...TENANT, version: -1 }])
        await expect(searchOrganizationDirectory('Компания')).rejects.toThrow('неотрицательным')
    })
})
