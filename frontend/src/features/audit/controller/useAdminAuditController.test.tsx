// frontend/src/features/audit/controller/useAdminAuditController.test.tsx
// ============================================================
// frontend/src/features/audit/controller/useAdminAuditController.test.tsx
// Регрессии фильтрации, полномочий, адресной строки и пагинации.
// ============================================================
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../auth/state/useAuth'
import type { AuthContextValue } from '../../auth/state/authContext.definition'
import useAuditDirectories from '../hooks/useAuditDirectories'
import useAuditEvents from '../hooks/useAuditEvents'
import { useAdminAuditController } from './useAdminAuditController'

vi.mock('../../auth/state/useAuth', () => ({ useAuth: vi.fn() }))
vi.mock('../hooks/useAuditDirectories', () => ({ default: vi.fn() }))
vi.mock('../hooks/useAuditEvents', () => ({ default: vi.fn() }))

const authMock = vi.mocked(useAuth)
const directoriesMock = vi.mocked(useAuditDirectories)
const eventsMock = vi.mocked(useAuditEvents)
const TENANT_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const OTHER_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

function setRole(isSuperAdmin: boolean) {
    const currentUser: NonNullable<AuthContextValue['currentUser']> = {
        id: '11111111-1111-4111-8111-111111111111',
        organizationId: TENANT_ID,
        email: 'admin@example.com',
        fullName: null,
        enabled: true,
        roles: isSuperAdmin ? ['SUPER_ADMIN'] : ['ADMIN'],
    }
    authMock.mockReturnValue({
        currentUser, authStatus: 'authenticated', authLoading: false,
        authError: null, loginUser: vi.fn(), logoutUser: vi.fn(),
        reloadCurrentUser: vi.fn(),
    })
}

beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/admin/audit')
    setRole(false)
    directoriesMock.mockReturnValue({
        eventTypes: ['USER_CREATED'], organizations: [], actors: [],
        loading: false, eventTypesError: '', organizationsError: '', actorsError: '',
        searchActors: vi.fn().mockResolvedValue(undefined),
        searchOrganizations: vi.fn().mockResolvedValue(undefined),
    })
    eventsMock.mockReturnValue({
        events: [],
        pageResponse: { content: [], page: 0, size: 50, totalPages: 0, totalElements: 0 },
        loading: false,
        error: '',
    })
})

describe('useAdminAuditController — контракты фильтров', () => {
    it('игнорирует целевую организацию из URL для обычного ADMIN', () => {
        window.history.replaceState({}, '', `/admin/audit?targetOrganizationId=${OTHER_ID}&page=2`)
        const { result } = renderHook(useAdminAuditController)
        expect(result.current.superAdmin).toBe(false)
        expect(result.current.draftFilter.targetOrganizationId).toBe('')
        expect(eventsMock).toHaveBeenCalledWith(expect.objectContaining({
            page: 2, filter: expect.objectContaining({ targetOrganizationId: undefined }),
        }))
        expect(window.location.search).not.toContain('targetOrganizationId')
    })

    it('сохраняет ограничение организации для SUPER_ADMIN и связывает его с запросом', () => {
        setRole(true)
        window.history.replaceState({}, '', `/admin/audit?targetOrganizationId=${OTHER_ID}`)
        const { result } = renderHook(useAdminAuditController)
        expect(result.current.draftFilter.targetOrganizationId).toBe(OTHER_ID)
        expect(eventsMock).toHaveBeenCalledWith(expect.objectContaining({
            filter: expect.objectContaining({ targetOrganizationId: OTHER_ID }),
        }))
    })

    it('не отправляет запросы с чужим targetOrganizationId при отзыве SUPER_ADMIN', async () => {
        setRole(true)
        window.history.replaceState({}, '', `/admin/audit?targetOrganizationId=${OTHER_ID}`)
        const { rerender } = renderHook(useAdminAuditController)
        eventsMock.mockClear()
        setRole(false)
        rerender()
        expect(eventsMock).toHaveBeenLastCalledWith(expect.objectContaining({
            filter: expect.objectContaining({ targetOrganizationId: undefined }),
        }))
        await waitFor(() => expect(window.location.search).not.toContain('targetOrganizationId'))
    })

    it('не применяет некорректный интервал и отображает ошибку фильтров', () => {
        const { result } = renderHook(useAdminAuditController)
        act(() => result.current.setDraftFilter({
            ...result.current.draftFilter,
            dateFrom: '2026-10-20', dateTo: '2026-10-19',
        }))
        const requests = eventsMock.mock.calls.length
        act(() => result.current.applyFilters())
        expect(result.current.filterError).not.toBe('')
        expect(eventsMock.mock.calls).toHaveLength(requests)
        expect(result.current.filtersDirty).toBe(true)
    })

    it('применяет валидный фильтр атомарно, сбрасывает страницу и сохраняет URL', () => {
        window.history.replaceState({}, '', '/admin/audit?page=3')
        const { result } = renderHook(useAdminAuditController)
        act(() => result.current.setDraftFilter({
            ...result.current.draftFilter, eventType: 'USER_CREATED',
            actorEmail: ' Demo@Example.COM ',
        }))
        expect(result.current.filtersDirty).toBe(true)
        act(() => result.current.applyFilters())
        expect(result.current.filtersDirty).toBe(false)
        expect(result.current.draftFilter.actorEmail).toBe('demo@example.com')
        expect(eventsMock).toHaveBeenLastCalledWith(expect.objectContaining({
            page: 0,
            filter: expect.objectContaining({
                eventType: 'USER_CREATED', actorEmail: 'demo@example.com',
            }),
        }))
        expect(window.location.search).toContain('eventType=USER_CREATED')
        expect(window.location.search).not.toContain('page=3')
    })

    it('сбрасывает фильтры, восстанавливая ограниченный диапазон', () => {
        const { result } = renderHook(useAdminAuditController)
        act(() => result.current.setDraftFilter({
            ...result.current.draftFilter, eventType: 'USER_CREATED',
        }))
        act(() => result.current.resetFilters())
        expect(result.current.draftFilter.eventType).toBe('')
        expect(result.current.draftFilter.dateFrom).toMatch(/^\d{4}-\d{2}-\d{2}$/)
        expect(result.current.filterError).toBe('')
    })

    it('меняет только черновик при выборе пресета до нажатия «Применить»', () => {
        const { result } = renderHook(useAdminAuditController)
        const before = eventsMock.mock.calls.length
        act(() => result.current.applyDatePreset('last7Days'))
        expect(result.current.draftFilter.dateFrom).toMatch(/^\d{4}-\d{2}-\d{2}$/)
        expect(eventsMock.mock.calls.length).toBe(before)
    })

    it('обрабатывает пагинацию, повтор загрузки и серверную коррекцию страницы', () => {
        const { result } = renderHook(useAdminAuditController)
        act(() => result.current.changePage(4))
        expect(eventsMock).toHaveBeenLastCalledWith(expect.objectContaining({ page: 4 }))
        act(() => result.current.retryLoad())
        expect(eventsMock).toHaveBeenLastCalledWith(expect.objectContaining({
            page: 4, reloadToken: 1,
        }))
        const args = eventsMock.mock.lastCall?.[0]
        expect(args).toBeDefined()
        act(() => args?.onPageOutOfRange(2))
        expect(eventsMock).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }))
    })

    it('показывает имя организации в подробностях через справочник', () => {
        directoriesMock.mockReturnValue({
            eventTypes: [], organizations: [{ targetOrganizationId: OTHER_ID, targetOrganizationName: 'Контур Б' }],
            actors: [], loading: false, eventTypesError: '', organizationsError: '', actorsError: '',
            searchActors: vi.fn().mockResolvedValue(undefined),
            searchOrganizations: vi.fn().mockResolvedValue(undefined),
        })
        const { result } = renderHook(useAdminAuditController)
        act(() => result.current.setSelectedEvent({
            id: '33333333-3333-4333-8333-333333333333',
            targetOrganizationId: OTHER_ID, targetOrganizationName: null,
            actorUserId: null, actorOrganizationId: null, actorEmail: null,
            actorDisplayName: null, eventType: 'USER_CREATED', details: {},
            detailsTruncated: false, detailsInvalid: false,
            createdAt: '2026-10-20T00:00:00Z',
        }))
        expect(result.current.selectedOrganizationFallback).toBe('Контур Б')
    })
})
