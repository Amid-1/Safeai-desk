// frontend/src/pages/KnowledgePage.test.tsx
// Регрессии каталога знаний: доступ, поиск, пагинация, конфликты и отмена запросов.
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../features/auth/api/authApi'
import { ApiError } from '../shared/api/http'
import {
    getKnowledgeBase,
    getKnowledgeBases,
    searchKnowledgeBases,
    updateKnowledgeBase,
} from '../entities/knowledge/api/knowledgeApi'
import type { KnowledgeBase } from '../entities/knowledge/api/knowledgeApi'
import KnowledgePage from './KnowledgePage'

const authMock = vi.hoisted(() => ({
    currentUser: null as AuthUser | null,
}))

vi.mock('../features/auth/state/useAuth', () => ({
    useAuth: () => authMock,
}))

vi.mock('../entities/knowledge/api/knowledgeApi', () => ({
    getKnowledgeBases: vi.fn(),
    getKnowledgeBase: vi.fn(),
    searchKnowledgeBases: vi.fn(),
    createKnowledgeBase: vi.fn(),
    updateKnowledgeBase: vi.fn(),
    getKnowledgeBaseMembers: vi.fn(),
    searchKnowledgeMemberCandidates: vi.fn(),
    addKnowledgeBaseMember: vi.fn(),
    updateKnowledgeBaseMember: vi.fn(),
    removeKnowledgeBaseMember: vi.fn(),
}))

vi.mock('../entities/knowledge/api/knowledgeDocumentApi', () => ({
    getKnowledgeHealth: vi.fn().mockResolvedValue({
        totalDocuments: 4,
        searchableDocuments: 3,
        pendingDocuments: 1,
        processingDocuments: 0,
    }),
}))

const listMock = vi.mocked(getKnowledgeBases)
const detailMock = vi.mocked(getKnowledgeBase)
const updateMock = vi.mocked(updateKnowledgeBase)
const searchMock = vi.mocked(searchKnowledgeBases)

function currentUser(role: 'ADMIN' | 'USER'): AuthUser {
    return {
        id: '11111111-1111-4111-8111-111111111111',
        organizationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        email: 'user@safeai.test',
        fullName: null,
        enabled: true,
        roles: [role],
    }
}

const testBase: KnowledgeBase = {
    id: '11111111-1111-4111-8111-111111111111',
    organizationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    name: 'Тестовая база',
    description: 'Документы для проверки',
    visibility: 'ORGANIZATION',
    enabled: true,
    createdByUserId: '11111111-1111-4111-8111-111111111111',
    version: 1,
    createdAt: '2026-10-17T00:00:00Z',
    updatedAt: '2026-10-17T00:00:00Z',
}

function displayPage() {
    return render(
        <MemoryRouter>
            <KnowledgePage />
        </MemoryRouter>,
    )
}

describe('KnowledgePage', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        authMock.currentUser = currentUser('ADMIN')
        listMock.mockResolvedValue({
            content: [], page: 0, size: 12,
            totalElements: 0, totalPages: 0,
        })
        searchMock.mockResolvedValue({
            content: [], page: 0, size: 12,
            totalElements: 0, totalPages: 0,
        })
    })

    it('ADMIN sees create action and filter toolbar', async () => {
        displayPage()
        expect(await screen.findByRole('button', {
            name: 'Создать базу знаний',
        })).toBeInTheDocument()
        expect(screen.getByRole('searchbox', {
            name: 'Поиск по названию',
        })).toBeInTheDocument()
        expect(listMock).toHaveBeenCalledWith(0, 12, expect.any(Object))
    })

    it('USER sees only read-only actions and no disabled filter', async () => {
        authMock.currentUser = currentUser('USER')
        displayPage()
        expect(await screen.findByText(
            'Вам пока не доступна ни одна база знаний.',
        )).toBeInTheDocument()
        expect(screen.queryByRole('button', {
            name: 'Создать базу знаний',
        })).not.toBeInTheDocument()
        expect(screen.queryByRole('option', {
            name: 'Отключённые',
        })).not.toBeInTheDocument()
    })

    it('filters all server pages, not just the visible 12 cards', async () => {
        displayPage()
        await screen.findByText('Баз знаний пока нет')
        fireEvent.change(screen.getByRole('searchbox', {
            name: 'Поиск по названию',
        }), { target: { value: 'Тестовая' } })

        await waitFor(() => {
            expect(searchMock).toHaveBeenCalledWith(
                0, 12,
                {
                    query: 'Тестовая',
                    enabled: undefined,
                    visibility: undefined,
                },
                expect.objectContaining({ signal: expect.any(AbortSignal) }),
            )
        })
        expect(await screen.findByText('Ничего не найдено')).toBeInTheDocument()
    })

    it('filters by activity and visibility; reset restores old listing', async () => {
        displayPage()
        await screen.findByText('Баз знаний пока нет')

        fireEvent.change(screen.getByRole('combobox', { name: 'Активность' }), {
            target: { value: 'disabled' },
        })
        fireEvent.change(screen.getByRole('combobox', { name: 'Доступность' }), {
            target: { value: 'MEMBERS' },
        })
        await waitFor(() => {
            expect(searchMock).toHaveBeenCalledWith(
                0, 12,
                { query: undefined, enabled: false, visibility: 'MEMBERS' },
                expect.any(Object),
            )
        })

        fireEvent.click(screen.getByRole('button', { name: 'Сбросить фильтры' }))
        await waitFor(() => {
            expect(listMock.mock.calls.length).toBeGreaterThan(1)
        })
    })

    it('renders paginated cards with their original access/settings controls', async () => {
        listMock.mockResolvedValue({
            content: [testBase], page: 0, size: 12,
            totalElements: 13, totalPages: 2,
        })
        displayPage()
        expect(await screen.findByRole('link', {
            name: 'Тестовая база',
        })).toBeInTheDocument()
        expect(screen.getByRole('button', {
            name: /Доступ/i,
        })).toBeInTheDocument()
        expect(screen.getByRole('button', {
            name: /Настройки/i,
        })).toBeInTheDocument()
        expect(screen.getByRole('navigation', {
            name: 'Пагинация баз знаний',
        })).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: 'Вперёд' }))
        await waitFor(() => {
            expect(listMock).toHaveBeenCalledWith(1, 12, expect.any(Object))
        })
    })
    it('обновляет expectedVersion после конкурентного редактирования', async () => {
        listMock.mockResolvedValue({
            content: [testBase], page: 0, size: 12,
            totalElements: 1, totalPages: 1,
        })
        updateMock.mockRejectedValueOnce(new ApiError(
            'База знаний уже изменён другим запросом: expectedVersion=1, actualVersion=2',
            { status: 409, error: 'CONFLICT' },
            409,
        ))
        detailMock.mockResolvedValue({
            ...testBase,
            name: 'Настройки после правки другого ADMIN',
            version: 2,
        })
        updateMock.mockResolvedValue({ ...testBase, version: 3 })

        displayPage()
        fireEvent.click(await screen.findByRole('button', { name: /Настройки/i }))
        fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
        expect(await screen.findByText(/База знаний была изменена параллельно/))
            .toBeInTheDocument()

        fireEvent.click(screen.getByRole('button', {
            name: 'Загрузить актуальные настройки',
        }))
        await waitFor(() => expect(detailMock).toHaveBeenCalledWith(testBase.id))
        await waitFor(() => expect(screen.getByRole('textbox', {
            name: 'Название',
        })).toHaveValue('Настройки после правки другого ADMIN'))

        fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
        await waitFor(() => expect(updateMock).toHaveBeenLastCalledWith(
            testBase.id,
            expect.objectContaining({ expectedVersion: 2 }),
        ))
    })


    it('при ошибке чтения не отображает устаревшие карточки и предлагает повтор', async () => {
        listMock.mockRejectedValueOnce(new Error('Временный отказ API'))
        displayPage()
        expect(await screen.findByText('Не удалось загрузить базы знаний.'))
            .toBeInTheDocument()
        expect(screen.queryByRole('link', { name: 'Тестовая база' }))
            .not.toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))
        await waitFor(() => expect(listMock).toHaveBeenCalledTimes(2))
        expect(await screen.findByText('Баз знаний пока нет')).toBeInTheDocument()
    })

    it('после ухода со страницы отменяет начальный запрос каталога', async () => {
        let signal: AbortSignal | undefined
        listMock.mockImplementationOnce(async (_page, _size, options) => {
            signal = options?.signal
            return new Promise<Awaited<ReturnType<typeof getKnowledgeBases>>>(() => undefined)
        })
        const page = displayPage()
        await waitFor(() => expect(listMock).toHaveBeenCalled())
        expect(signal?.aborted).toBe(false)
        page.unmount()
        expect(signal?.aborted).toBe(true)
    })
})
