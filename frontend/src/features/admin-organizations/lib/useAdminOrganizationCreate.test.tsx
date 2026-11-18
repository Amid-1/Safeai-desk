import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createOrganization } from '../../../entities/organization/api/organizationApi'
import { useAdminOrganizationCreate } from './useAdminOrganizationCreate'

vi.mock('../../../entities/organization/api/organizationApi', async importOriginal => ({
    ...(await importOriginal<typeof import('../../../entities/organization/api/organizationApi')>()),
    createOrganization: vi.fn(),
}))

const created = {
    id: 'af08bde0-df58-411d-9c64-35638dbf1c0a', name: 'Новый клиент', type: 'TENANT' as const,
    enabled: true, version: 0, protected: false,
    createdAt: '2026-11-18T10:00:00Z', updatedAt: '2026-11-18T10:00:00Z',
}

beforeEach(() => { vi.resetAllMocks() })

function setup() {
    const reload = vi.fn()
    const success = vi.fn()
    const { result } = renderHook(() => useAdminOrganizationCreate({
        requestReloadFromFirstPage: reload, setSuccess: success,
    }))
    return { result, reload, success }
}

describe('Organization creation result and draft', () => {
    it('returns success only after the server confirms creation and sends only the name', async () => {
        vi.mocked(createOrganization).mockResolvedValue(created)
        const { result, reload, success } = setup()
        act(() => result.current.setName(' Новый клиент '))
        await act(async () => { expect(await result.current.submitCreateOrganization()).toBe(true) })
        expect(createOrganization).toHaveBeenCalledWith({ name: 'Новый клиент' })
        expect(result.current.name).toBe('')
        expect(reload).toHaveBeenCalledOnce()
        expect(success).toHaveBeenLastCalledWith('Организация «Новый клиент» создана.')
    })

    it('retains the draft on failure without falsely signalling success or reloading', async () => {
        vi.mocked(createOrganization).mockRejectedValue(new Error('private backend details'))
        const { result, reload, success } = setup()
        act(() => result.current.setName('Новый клиент'))
        await act(async () => { expect(await result.current.submitCreateOrganization()).toBe(false) })
        expect(result.current.name).toBe('Новый клиент')
        expect(result.current.createError).toBe('Не удалось создать организацию.')
        expect(reload).not.toHaveBeenCalled()
        expect(success).toHaveBeenLastCalledWith('')
    })

    it('rejects a duplicate submit while the first request is pending', async () => {
        let resolve!: (value: typeof created) => void
        vi.mocked(createOrganization).mockReturnValue(new Promise(done => { resolve = done }))
        const { result } = setup()
        act(() => result.current.setName('Новый клиент'))
        let first!: Promise<boolean>
        act(() => { first = result.current.submitCreateOrganization() })
        expect(result.current.creating).toBe(true)
        await act(async () => { expect(await result.current.submitCreateOrganization()).toBe(false) })
        expect(createOrganization).toHaveBeenCalledOnce()
        await act(async () => { resolve(created); expect(await first).toBe(true) })
    })
})
