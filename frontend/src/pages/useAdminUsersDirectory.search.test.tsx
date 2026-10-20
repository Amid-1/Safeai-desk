// frontend/src/components/admin/users/useAdminUsersDirectory.search.test.tsx
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../api/authApi'
import type { User, UserStatistics } from '../api/userApi'
import { getUserStatistics, searchUsers } from '../api/userApi'
import { useAdminUsersDirectory } from './useAdminUsersDirectory'

vi.mock('../api/userApi', () => ({
    searchUsers: vi.fn(),
    getUserStatistics: vi.fn(),
    getUserDetails: vi.fn(),
}))
vi.mock('../api/organizationApi', () => ({
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
    vi.clearAllMocks()
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
        const { result } = renderHook(() => useAdminUsersDirectory(currentUser, false))
        await waitFor(() => expect(result.current.users).toHaveLength(10))

        act(() => result.current.setSearchDraft('user5'))
        await waitFor(() => expect(latestQuery()).toBe('user5'))
        await waitFor(() => expect(result.current.users).toHaveLength(1))

        act(() => result.current.setSearchDraft(''))
        await waitFor(() => expect(latestQuery()).toBeUndefined())
        await waitFor(() => expect(result.current.users).toHaveLength(10))
        expect(result.current.statistics.total).toBe(10)
    })

    it('finds a user by surname, given name and stored full name', async () => {
        const { result } = renderHook(() => useAdminUsersDirectory(currentUser, false))
        await waitFor(() => expect(result.current.users).toHaveLength(10))
        for (const query of ['Петров', 'Иван', 'Иван Петров']) {
            act(() => result.current.setSearchDraft(query))
            await waitFor(() => expect(latestQuery()).toBe(query))
            await waitFor(() => expect(result.current.users[0]?.email).toBe('user5@test.com'))
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
})
