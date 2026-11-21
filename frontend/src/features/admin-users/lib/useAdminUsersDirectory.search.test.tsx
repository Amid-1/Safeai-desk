// frontend/src/features/admin-users/lib/useAdminUsersDirectory.search.test.tsx
// frontend/src/features/admin-users/lib/useAdminUsersDirectory.search.test.tsx
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../../auth/api/authApi'
import type { User, UserStatistics } from '../../../entities/user/api/userApi'
import { getUserDetails, getUserStatistics, searchUsers } from '../../../entities/user/api/userApi'
import { searchOrganizationDirectory } from '../../../entities/organization/api/organizationApi'
import { useAdminUsersDirectory } from './useAdminUsersDirectory'

vi.mock('../../../entities/user/api/userApi', () => ({
    searchUsers: vi.fn(),
    getUserStatistics: vi.fn(),
    getUserDetails: vi.fn(),
}))
vi.mock('../../../entities/organization/api/organizationApi', () => ({
    searchOrganizationDirectory: vi.fn(),
    isProtectedOrganization: vi.fn(),
}))

const ORGANIZATION = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const currentUser: AuthUser = {
    id: '11111111-1111-1111-1111-111111111111',
    organizationId: ORGANIZATION,
    email: 'admin@test.com',
    fullName: 'Администратор',
    enabled: true,
    roles: ['ADMIN'],
}
const users: User[] = Array.from({ length: 10 }, (_, index) => ({
    id: `11111111-1111-1111-1111-${String(index + 2).padStart(12, '0')}`,
    organizationId: ORGANIZATION,
    email: `user${index}@test.com`,
    fullName: index === 5 ? 'Иван Петров' : `UserTest${index} UserTestov`,
    enabled: true,
    roles: ['USER'],
    version: 1,
    createdAt: '2026-08-21T15:07:59Z',
    updatedAt: '2026-08-21T15:07:59Z',
    lastLoginAt: null,
}))
const statistics: UserStatistics = {
    total: 10,
    administrators: 1,
    users: 9,
    enabled: 10,
    disabled: 0,
}

function latestQuery(): string | undefined {
    const calls = vi.mocked(searchUsers).mock.calls
    return calls[calls.length - 1]?.[2]?.query
}

beforeEach(() => {
    window.history.replaceState({}, '', '/admin/users')
    vi.clearAllMocks()
    vi.mocked(searchOrganizationDirectory).mockResolvedValue([])
    vi.mocked(getUserStatistics).mockResolvedValue(statistics)
    vi.mocked(searchUsers).mockImplementation(async (_page, _size, filters) => {
        const query = (filters?.query || '').toLocaleLowerCase()
        const filtered = users.filter(user =>
            !query
            || user.email.toLocaleLowerCase().includes(query)
            || (user.fullName || '').toLocaleLowerCase().includes(query),
        )
        return {
            content: filtered,
            page: 0,
            size: 50,
            totalElements: filtered.length,
            totalPages: filtered.length ? 1 : 0,
        }
    })
})

describe('Users directory: server-side search and reset', () => {
    it('automatically applies a query and restores the whole list when cleared', async () => {
        vi.useFakeTimers()
        try {
            const { result } = renderHook(() => useAdminUsersDirectory(currentUser, false))
            await act(async () => { await vi.advanceTimersByTimeAsync(0) })
            expect(result.current.users).toHaveLength(10)
            const initialCalls = vi.mocked(searchUsers).mock.calls.length

            act(() => result.current.setSearchDraft('user5'))
            await act(async () => { await vi.advanceTimersByTimeAsync(299) })
            expect(searchUsers).toHaveBeenCalledTimes(initialCalls)
            expect(latestQuery()).toBeUndefined()
            await act(async () => { await vi.advanceTimersByTimeAsync(1) })
            expect(searchUsers).toHaveBeenCalledTimes(initialCalls + 1)
            expect(latestQuery()).toBe('user5')
            expect(result.current.users).toHaveLength(1)
            expect(result.current.users[0]?.email).toBe('user5@test.com')

            // Clearing resets immediately; no second 300 ms debounce.
            await act(async () => { result.current.setSearchDraft('') })
            expect(searchUsers).toHaveBeenCalledTimes(initialCalls + 2)
            expect(latestQuery()).toBeUndefined()
            expect(result.current.users).toHaveLength(10)
            expect(result.current.statistics.total).toBe(10)
        } finally {
            vi.useRealTimers()
        }
    })

    it('finds a user by surname, given name and stored full name', async () => {
        const { result } = renderHook(() => useAdminUsersDirectory(currentUser, false))
        await waitFor(() => expect(result.current.users).toHaveLength(10))
        for (const query of ['И', 'Петров', 'Иван', '  ИВАН ПЕТРОВ  ']) {
            act(() => result.current.setSearchDraft(query))
            await waitFor(() => expect(latestQuery()).toBe(query.trim()))
            await waitFor(() => expect(result.current.users[0]?.email).toBe('user5@test.com'))
        }
    })

    it('сбрасывает страницу, отменяет старый запрос и сохраняет фильтры при быстром вводе', async () => {
        window.history.replaceState({}, '', '/admin/users?role=USER&status=ACTIVE&page=2')
        vi.mocked(searchUsers).mockResolvedValueOnce({
            content: users, page: 2, size: 50, totalElements: 110, totalPages: 3,
        })
        vi.useFakeTimers()
        try {
            const {result} = renderHook(() => useAdminUsersDirectory(currentUser, false))
            await act(async () => { await vi.advanceTimersByTimeAsync(0) })
            expect(result.current.page).toBe(2)
            const initial = vi.mocked(searchUsers).mock.calls.length
            act(() => result.current.setSearchDraft('u'))
            await act(async () => { await vi.advanceTimersByTimeAsync(200) })
            act(() => result.current.setSearchDraft('USER5'))
            await act(async () => { await vi.advanceTimersByTimeAsync(299) })
            expect(searchUsers).toHaveBeenCalledTimes(initial)
            await act(async () => { await vi.advanceTimersByTimeAsync(1) })
            expect(searchUsers).toHaveBeenCalledTimes(initial + 1)
            expect(vi.mocked(searchUsers).mock.lastCall?.[2]).toMatchObject({
                query: 'USER5', role: 'USER', enabled: true,
            })
            expect(vi.mocked(searchUsers).mock.calls[initial - 1]?.[3]?.signal?.aborted).toBe(true)
            expect(result.current.page).toBe(0)
            expect(result.current.users.map(user => user.email)).toEqual(['user5@test.com'])
        } finally {
            vi.useRealTimers()
        }
    })

    it('игнорирует запоздалый ответ отменённого запроса', async () => {
        let finishOld!: (value: Awaited<ReturnType<typeof searchUsers>>) => void
        const response = new Promise<Awaited<ReturnType<typeof searchUsers>>>(resolve => { finishOld = resolve })
        vi.mocked(searchUsers).mockReturnValueOnce(response)
        vi.useFakeTimers()
        try {
            const {result} = renderHook(() => useAdminUsersDirectory(currentUser, false))
            const oldSignal = vi.mocked(searchUsers).mock.lastCall?.[3]?.signal
            act(() => result.current.setSearchDraft('USER5'))
            await act(async () => { await vi.advanceTimersByTimeAsync(300) })
            expect(oldSignal?.aborted).toBe(true)
            expect(result.current.users.map(user => user.email)).toEqual(['user5@test.com'])
            await act(async () => finishOld({content: users, page: 0, size: 50, totalElements: 10, totalPages: 1}))
            expect(result.current.users.map(user => user.email)).toEqual(['user5@test.com'])
            expect(result.current.totalMatched).toBe(1)
        } finally {
            vi.useRealTimers()
        }
    })

    it('keeps overall counters when there are no matches and resets the search', async () => {
        const { result } = renderHook(() => useAdminUsersDirectory(currentUser, false))
        act(() => result.current.setSearchDraft('nonexistent@example.com'))
        await waitFor(() => expect(result.current.totalMatched).toBe(0))
        await waitFor(() => expect(latestQuery()).toBe('nonexistent@example.com'))
        expect(result.current.statistics.total).toBe(10)
        act(() => result.current.clearSearch())
        await waitFor(() => expect(result.current.users).toHaveLength(10))
    })

    it('selects a unique email and forwards role/status predicates to the server', async () => {
        const { result } = renderHook(() => useAdminUsersDirectory(currentUser, false))
        await waitFor(() => expect(result.current.users).toHaveLength(10))
        act(() => result.current.selectSearchSuggestion(users[5]!))
        await waitFor(() => expect(latestQuery()).toBe('user5@test.com'))
        act(() => {
            result.current.changeFilter('USER')
            result.current.changeStatus('ACTIVE')
        })
        await waitFor(() => {
            const filters = vi.mocked(searchUsers).mock.lastCall?.[2]
            expect(filters).toMatchObject({
                query: 'user5@test.com', role: 'USER', enabled: true,
            })
        })
    })

    it('restores organization scope and role from a direct link, and resets page on change', async () => {
        window.history.replaceState({}, '', `/admin/users?organizationId=${ORGANIZATION}&role=SUPER_ADMIN&page=2`)
        const superAdmin: AuthUser = {...currentUser, roles: ['SUPER_ADMIN']}
        const {result} = renderHook(() => useAdminUsersDirectory(superAdmin, true))
        await waitFor(() => expect(vi.mocked(searchUsers).mock.calls[0]?.[2]).toMatchObject({
            organizationId: ORGANIZATION, role: 'SUPER_ADMIN',
        }))
        expect(vi.mocked(getUserStatistics).mock.calls[0]?.[0]).toMatchObject({
            directory: true, organizationId: ORGANIZATION,
        })
        act(() => result.current.changeOrganization(''))
        await waitFor(() => expect(vi.mocked(searchUsers).mock.lastCall?.[2]?.organizationId).toBeUndefined())
        expect(result.current.page).toBe(0)
        expect(window.location.search).not.toContain('organizationId')
    })

    it('does not forward a foreign scope or SUPER_ADMIN role for a tenant administrator', async () => {
        window.history.replaceState({}, '', '/admin/users?organizationId=bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb&role=SUPER_ADMIN')
        renderHook(() => useAdminUsersDirectory(currentUser, false))
        await waitFor(() => expect(searchUsers).toHaveBeenCalled())
        expect(vi.mocked(searchUsers).mock.lastCall?.[2]?.organizationId).toBeUndefined()
        expect(vi.mocked(searchUsers).mock.lastCall?.[2]?.role).toBeUndefined()
    })

    it('opens an exact off-page user via the protected details endpoint', async () => {
        const details = {...users[0]!, id: '22222222-2222-4222-8222-222222222222', organizationName: 'Компания'}
        vi.mocked(getUserDetails).mockResolvedValueOnce(details)
        window.history.replaceState({}, '', `/admin/users?userId=${details.id}`)
        const {result} = renderHook(() => useAdminUsersDirectory({...currentUser, roles: ['SUPER_ADMIN']}, true))
        await waitFor(() => expect(result.current.detailsUser).toEqual(details))
        expect(getUserDetails).toHaveBeenCalledWith(details.id, {signal: expect.any(AbortSignal)})
        expect(result.current.users.every(user => user.id !== details.id)).toBe(true)
    })

    it('never renders a cross-tenant response to ADMIN and hides technical failures', async () => {
        const details = {...users[0]!, organizationId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', organizationName: 'Чужая'}
        vi.mocked(getUserDetails).mockResolvedValueOnce(details)
        window.history.replaceState({}, '', `/admin/users?userId=${details.id}`)
        const {result} = renderHook(() => useAdminUsersDirectory(currentUser, false))
        await waitFor(() => expect(result.current.loadError).toContain('Карточка пользователя недоступна'))
        expect(result.current.detailsUser).toBeNull()
    })
})
