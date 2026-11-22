// frontend/src/features/admin-organizations/lib/useAdminOrganizationsDirectory.test.tsx
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getOrganizationDetails, getOrganizations } from '../../../entities/organization/api/organizationApi'
import type { Organization } from '../../../entities/organization/api/organizationApi'
import { useAdminOrganizationsDirectory } from './useAdminOrganizationsDirectory'

vi.mock('../../../entities/organization/api/organizationApi', () => ({
    getOrganizationDetails: vi.fn(),
    getOrganizations: vi.fn(),
}))

const organization: Organization = {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    name: 'Компания А', type: 'TENANT', enabled: true, protected: false,
    version: 1, createdAt: '2026-01-01T12:00:00Z', updatedAt: '2026-01-01T12:00:00Z',
}
const other = {...organization, id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', name: 'Компания Б'}
const listing = {content: [organization], page: 0, size: 50, totalElements: 1, totalPages: 1}

beforeEach(() => {
    vi.resetAllMocks()
    window.history.replaceState({}, '', '/admin/organizations')
    vi.mocked(getOrganizations).mockResolvedValue(listing)
    vi.mocked(getOrganizationDetails).mockResolvedValue(organization)
})

describe('Каталог организаций', () => {
    it('сохраняет результаты поиска при отказе общих итогов', async () => {
        window.history.replaceState({}, '', '/admin/organizations?query=Компания')
        vi.mocked(getOrganizations).mockImplementation(async (_page, size) => {
            if (size === 1) throw new Error('unavailable')
            return listing
        })
        const {result} = renderHook(useAdminOrganizationsDirectory)
        await waitFor(() => expect(result.current.loading).toBe(false))
        expect(result.current.organizations).toEqual([organization])
        expect(result.current.totalMatched).toBe(1)
        expect(result.current.totalOrganizations).toBeNull()
        expect(result.current.loadError).toBe('')
        vi.mocked(getOrganizations).mockResolvedValue({...listing, totalElements: 5})
        act(() => result.current.reloadCurrentPage())
        await waitFor(() => expect(result.current.totalOrganizations).toBe(5))
    })

    it('не показывает карточку другого объекта по прямой ссылке', async () => {
        window.history.replaceState({}, '', '/admin/organizations?organizationId=' + other.id)
        const {result} = renderHook(useAdminOrganizationsDirectory)
        await waitFor(() => expect(result.current.detailsError).toContain('недоступна'))
        expect(result.current.detailsOrganization).toBeNull()
        expect(result.current.loadError).toBe('')
    })

    it('закрытие карточки игнорирует запоздалый ответ', async () => {
        let resolve!: (row: Organization) => void
        vi.mocked(getOrganizationDetails).mockReturnValue(new Promise(done => { resolve = done }))
        window.history.replaceState({}, '', '/admin/organizations?organizationId=' + organization.id)
        const {result} = renderHook(useAdminOrganizationsDirectory)
        await waitFor(() => expect(result.current.detailsLoadingId).toBe(organization.id))
        act(() => result.current.closeDetailsModal())
        await act(async () => resolve(organization))
        expect(result.current.detailsOrganization).toBeNull()
        expect(result.current.detailsLoadingId).toBeNull()
    })

    it('не открывает устаревшую карточку при отказе запроса', async () => {
        const {result} = renderHook(useAdminOrganizationsDirectory)
        vi.mocked(getOrganizationDetails).mockRejectedValueOnce(new Error('unavailable'))
        await act(async () => result.current.openDetailsModal(organization))
        expect(result.current.detailsOrganization).toBeNull()
        expect(result.current.detailsError).not.toBe('')
    })
})
