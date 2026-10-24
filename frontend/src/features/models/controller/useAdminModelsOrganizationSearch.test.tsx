// frontend/src/features/models/controller/useAdminModelsOrganizationSearch.test.tsx
// ============================================================
// frontend/src/features/models/controller/useAdminModelsOrganizationSearch.test.tsx
// Регрессия поиска: debounce, роли, пустые результаты, ошибки и гонки.
// ============================================================
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { searchOrganizationDirectory } from '../../../entities/organization/api/organizationApi'
import type { OrganizationDirectoryItem } from '../../../entities/organization/api/organizationApi'
import { useAdminModelsOrganizationSearch } from './useAdminModelsOrganizationSearch'

vi.mock('../../../entities/organization/api/organizationApi', () => ({
    searchOrganizationDirectory: vi.fn(),
}))

const ORG: OrganizationDirectoryItem = {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    name: 'Ромашка',
    type: 'TENANT',
    enabled: true,
    protected: false,
    version: 1,
}
const search = vi.mocked(searchOrganizationDirectory)

beforeEach(() => {
    vi.resetAllMocks()
    vi.useFakeTimers()
})
afterEach(() => {
    vi.useRealTimers()
})

async function advance(ms = 300) {
    await act(async () => {
        await vi.advanceTimersByTimeAsync(ms)
    })
}

describe('useAdminModelsOrganizationSearch', () => {
    it('не обращается к API для ADMIN даже при прямом запросе', async () => {
        const { result } = renderHook(() => useAdminModelsOrganizationSearch(false))
        act(() => result.current.handleOrganizationQueryChange('Ромашка'))
        await act(async () => result.current.handleOrganizationSearch())
        await advance()
        expect(search).not.toHaveBeenCalled()
        expect(result.current.organizationResults).toEqual([])
    })

    it('ждёт два символа и выполняет один запрос после debounce', async () => {
        search.mockResolvedValue([ORG])
        const { result } = renderHook(() => useAdminModelsOrganizationSearch(true))
        act(() => result.current.handleOrganizationQueryChange('Р'))
        await advance(450)
        expect(search).not.toHaveBeenCalled()
        act(() => result.current.handleOrganizationQueryChange('Ромашка'))
        await advance(299)
        expect(search).not.toHaveBeenCalled()
        await advance(1)
        expect(search).toHaveBeenCalledTimes(1)
        expect(search).toHaveBeenCalledWith('Ромашка', 12, {
            signal: expect.any(AbortSignal),
        })
        expect(result.current.organizationSearchStatus).toBe('success')
        expect(result.current.organizationResults).toEqual([ORG])
    })

    it('показывает пустой результат без общего page-level error', async () => {
        search.mockResolvedValue([])
        const { result } = renderHook(() => useAdminModelsOrganizationSearch(true))
        act(() => result.current.handleOrganizationQueryChange('Нет такой'))
        await advance()
        expect(result.current.organizationSearchStatus).toBe('empty')
        expect(result.current.organizationSearchError).toBe('')
    })

    it('отделяет ошибку поиска от ошибки загрузки настроек моделей', async () => {
        search.mockRejectedValue(new Error('Не удалось найти организацию.'))
        const { result } = renderHook(() => useAdminModelsOrganizationSearch(true))
        act(() => result.current.handleOrganizationQueryChange('Ромашка'))
        await advance()
        expect(result.current.organizationSearchStatus).toBe('error')
        expect(result.current.organizationSearchError).toBeTruthy()
        expect(result.current.organizationResults).toEqual([])
    })

    it('не принимает ответ старого запроса, даже если мок игнорирует abort', async () => {
        let resolveFirst!: (items: OrganizationDirectoryItem[]) => void
        search.mockReturnValueOnce(new Promise<OrganizationDirectoryItem[]>(resolve => { resolveFirst = resolve }))
        search.mockResolvedValueOnce([ORG])
        const { result } = renderHook(() => useAdminModelsOrganizationSearch(true))
        act(() => result.current.handleOrganizationQueryChange('Старая'))
        await advance()
        act(() => result.current.handleOrganizationQueryChange('Ромашка'))
        await advance()
        expect(result.current.organizationResults).toEqual([ORG])
        await act(async () => { resolveFirst([{ ...ORG, name: 'Чужая' }]) })
        expect(result.current.organizationResults).toEqual([ORG])
    })

    it('при отзыве SUPER_ADMIN сбрасывает результаты и отменяет запрос', async () => {
        search.mockResolvedValue([ORG])
        const { result, rerender } = renderHook(
            ({ allowed }) => useAdminModelsOrganizationSearch(allowed),
            { initialProps: { allowed: true } },
        )
        act(() => result.current.handleOrganizationQueryChange('Ромашка'))
        await advance()
        expect(result.current.organizationResults).toHaveLength(1)
        rerender({ allowed: false })
        expect(result.current.organizationSearchStatus).toBe('idle')
        expect(result.current.organizationResults).toHaveLength(0)
    })

    it('выполняет поиск по Enter/Найти сразу и не дублирует запрос таймером', async () => {
        search.mockResolvedValue([ORG])
        const { result } = renderHook(() => useAdminModelsOrganizationSearch(true))
        act(() => result.current.handleOrganizationQueryChange('Ромашка'))
        await act(async () => result.current.handleOrganizationSearch())
        await advance(500)
        expect(search).toHaveBeenCalledTimes(1)
    })
})
