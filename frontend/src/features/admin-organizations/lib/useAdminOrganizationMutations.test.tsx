// frontend/src/features/admin-organizations/lib/useAdminOrganizationMutations.test.tsx
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
    disableOrganization, getOrganizationDisableImpact, updateOrganizationName,
} from '../../../entities/organization/api/organizationApi'
import type { Organization, OrganizationDisableImpact } from '../../../entities/organization/api/organizationApi'
import { ApiError } from '../../../shared/api/http'
import { useAdminOrganizationMutations } from './useAdminOrganizationMutations'

vi.mock('../../../entities/organization/api/organizationApi', async importOriginal => ({
    ...await importOriginal<typeof import('../../../entities/organization/api/organizationApi')>(),
    disableOrganization: vi.fn(),
    getOrganizationDisableImpact: vi.fn(),
    updateOrganizationName: vi.fn(),
}))

const organization: Organization = {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', name: 'Компания А',
    type: 'TENANT', enabled: true, protected: false, version: 2,
    createdAt: '2026-01-01T12:00:00Z', updatedAt: '2026-01-01T12:00:00Z',
}
const impact: OrganizationDisableImpact = {
    organizationId: organization.id, organizationVersion: organization.version,
    enabledUsers: 3, administrators: 1, activeRefreshSessions: 2, activeChatOperations: 0,
}

beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(getOrganizationDisableImpact).mockResolvedValue(impact)
    vi.mocked(disableOrganization).mockResolvedValue({...organization, enabled: false})
})

describe('Безопасные изменения организации', () => {
    it.each([
        {...impact, organizationId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'},
        {...impact, organizationVersion: 1},
    ])('не открывает подтверждение для чужого или устаревшего расчёта', async response => {
        vi.mocked(getOrganizationDisableImpact).mockResolvedValueOnce(response)
        const reload = vi.fn()
        const {result} = renderHook(() => useAdminOrganizationMutations({
            requestReloadFromFirstPage: reload,
        }))
        await act(async () => result.current.openDisableDialog(organization))
        expect(result.current.disableDialog).toBeNull()
        expect(result.current.mutationError).not.toBe('')
        expect(reload).toHaveBeenCalledOnce()
        expect(disableOrganization).not.toHaveBeenCalled()
    })

    it('блокирует второе действие во время расчёта последствий', async () => {
        let resolve!: (value: OrganizationDisableImpact) => void
        vi.mocked(getOrganizationDisableImpact).mockReturnValue(new Promise(done => { resolve = done }))
        const {result} = renderHook(() => useAdminOrganizationMutations({
            requestReloadFromFirstPage: vi.fn(),
        }))
        act(() => { void result.current.openDisableDialog(organization) })
        act(() => result.current.openRenameModal(organization))
        expect(result.current.renameOrganization).toBeNull()
        await act(async () => resolve(impact))
        expect(result.current.disableDialog?.impact).toEqual(impact)
    })

    it('отправляет только одно подтверждение при двойном нажатии', async () => {
        const {result} = renderHook(() => useAdminOrganizationMutations({
            requestReloadFromFirstPage: vi.fn(),
        }))
        await act(async () => result.current.openDisableDialog(organization))
        act(() => result.current.setDisableConfirmationName(organization.name))
        await act(async () => {
            await Promise.all([
                result.current.confirmDisableOrganization(),
                result.current.confirmDisableOrganization(),
            ])
        })
        expect(disableOrganization).toHaveBeenCalledExactlyOnceWith(organization.id, {
            expectedVersion: 2, confirmationName: organization.name,
        })
    })

    it('закрывает устаревшее подтверждение при конфликте версии', async () => {
        vi.mocked(updateOrganizationName).mockRejectedValue(new ApiError(
            'conflict', {code: 'ORGANIZATION_VERSION_CONFLICT'}, 409,
        ))
        const reload = vi.fn()
        const {result} = renderHook(() => useAdminOrganizationMutations({
            requestReloadFromFirstPage: reload,
        }))
        act(() => result.current.openRenameModal(organization))
        act(() => result.current.setRenameValue('Новое название'))
        await act(async () => result.current.submitRenameOrganization())
        expect(result.current.renameOrganization).toBeNull()
        expect(result.current.mutationError).toContain('другим администратором')
        expect(reload).toHaveBeenCalledOnce()
    })
})
