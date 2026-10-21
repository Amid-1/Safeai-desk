// ============================================================
// frontend/src/pages/AdminModelsPage.production.test.tsx
// ============================================================
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../api/authApi'
import type { ModelRouteDecision, OrganizationModelPolicy } from '../api/modelApi'
import {
    createOrganizationModelPolicyVersion,
    getEffectiveModelCatalog,
    getModelCatalog,
    getModelRouteDecision,
    getOrganizationModelPolicy,
    getRuntimeModelStatus,
    importRuntimeModelCatalog,
    probeRuntimeModel,
} from '../api/modelApi'
import { getOrganizationDetails, searchOrganizationDirectory } from '../api/organizationApi'
import {
    createModelCatalogEntry,
    createOrganizationModelPolicy,
    createRuntimeModelStatus,
} from '../components/admin/models/ModelControlPlaneTestFixtures'
import AdminModelsPage from './AdminModelsPage'

const auth = vi.hoisted(() => ({ currentUser: null as AuthUser | null }))
vi.mock('../auth/useAuth', () => ({ useAuth: () => auth }))
vi.mock('../api/modelApi', async (importOriginal) => ({
    ...await importOriginal<typeof import('../api/modelApi')>(),
    getRuntimeModelStatus: vi.fn(),
    getModelCatalog: vi.fn(),
    getEffectiveModelCatalog: vi.fn(),
    getOrganizationModelPolicy: vi.fn(),
    createModelCatalogVersion: vi.fn(),
    createOrganizationModelPolicyVersion: vi.fn(),
    importRuntimeModelCatalog: vi.fn(),
    probeRuntimeModel: vi.fn(),
    getModelRouteDecision: vi.fn(),
    previewOrganizationModelPolicy: vi.fn(),
}))
vi.mock('../api/organizationApi', async (importOriginal) => ({
    ...await importOriginal<typeof import('../api/organizationApi')>(),
    getOrganizationDetails: vi.fn(),
    searchOrganizationDirectory: vi.fn(),
}))

const ORG = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const SECOND_ORG = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const ROUTE_ID = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'

function user(role: 'ADMIN' | 'SUPER_ADMIN'): AuthUser {
    return {
        id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
        organizationId: ORG,
        email: 'admin@safeai.test',
        fullName: 'Admin',
        enabled: true,
        roles: [role],
    }
}

function deferred<T>() {
    let resolve!: (value: T) => void
    let reject!: (reason: Error) => void
    const promise = new Promise<T>((yes, no) => {
        resolve = yes
        reject = no
    })
    return { promise, resolve, reject }
}

function configureApi() {
    vi.mocked(getRuntimeModelStatus).mockResolvedValue(createRuntimeModelStatus())
    vi.mocked(getModelCatalog).mockResolvedValue([createModelCatalogEntry()])
    vi.mocked(getEffectiveModelCatalog).mockResolvedValue([createModelCatalogEntry()])
    vi.mocked(getOrganizationModelPolicy).mockImplementation(async organizationId =>
        createOrganizationModelPolicy({ organizationId }),
    )
    vi.mocked(getOrganizationDetails).mockImplementation(async organizationId => ({
        id: organizationId,
        name: organizationId === SECOND_ORG ? 'Second Organization' : 'First Organization',
        enabled: true,
        type: 'TENANT',
        protected: false,
        version: 1,
        createdAt: '2026-09-01T00:00:00Z',
        updatedAt: '2026-09-01T00:00:00Z',
    } as Awaited<ReturnType<typeof getOrganizationDetails>>))
    vi.mocked(searchOrganizationDirectory).mockResolvedValue([])
}

async function loadPage() {
    render(<AdminModelsPage />)
    await screen.findByRole('heading', { name: 'Модели и маршрутизация' })
}

describe('AdminModelsPage — контрактные и регрессионные сценарии', () => {
    beforeEach(() => {
        vi.resetAllMocks()
        auth.currentUser = user('ADMIN')
        configureApi()
    })

    it('показывает ошибку начальной загрузки, а не частично сформированную страницу', async () => {
        vi.mocked(getRuntimeModelStatus).mockRejectedValueOnce(new Error('runtime unavailable'))
        render(<AdminModelsPage />)
        expect(await screen.findByText('Не удалось загрузить настройки моделей.')).toBeInTheDocument()
        expect(screen.queryByRole('heading', { name: 'Модели и маршрутизация' }))
            .not.toBeInTheDocument()
    })

    it('не отображает политику другой организации, даже при некорректном успешном GET', async () => {
        vi.mocked(getOrganizationModelPolicy).mockResolvedValueOnce(
            createOrganizationModelPolicy({ organizationId: SECOND_ORG }),
        )
        render(<AdminModelsPage />)
        expect(await screen.findByText(/Сервер вернул правила другой организации/))
            .toBeInTheDocument()
        expect(screen.queryByRole('heading', { name: 'Модели и маршрутизация' }))
            .not.toBeInTheDocument()
    })

    it('не показывает старую политику во время смены организации', async () => {
        auth.currentUser = user('SUPER_ADMIN')
        const secondPolicy = deferred<OrganizationModelPolicy>()
        vi.mocked(getOrganizationModelPolicy).mockImplementation(organizationId =>
            organizationId === SECOND_ORG
                ? secondPolicy.promise
                : Promise.resolve(createOrganizationModelPolicy({ organizationId })),
        )
        vi.mocked(searchOrganizationDirectory).mockResolvedValue([{
            id: SECOND_ORG,
            name: 'Second Organization',
            enabled: true,
            type: 'TENANT',
            protected: false,
            version: 1,
        }] as Awaited<ReturnType<typeof searchOrganizationDirectory>>)

        await loadPage()
        fireEvent.change(screen.getByPlaceholderText('Название или UUID'), {
            target: { value: 'Second' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Найти' }))
        fireEvent.click(await screen.findByRole('button', { name: /Second Organization/ }))

        expect(screen.queryByRole('heading', { name: 'Модели и маршрутизация' }))
            .not.toBeInTheDocument()
        expect(screen.getByText('Загрузка настроек моделей...')).toBeInTheDocument()

        secondPolicy.resolve(createOrganizationModelPolicy({ organizationId: SECOND_ORG }))
        await screen.findByRole('heading', { name: 'Модели и маршрутизация' })
        await waitFor(() => {
            expect(screen.getAllByText(SECOND_ORG).length).toBeGreaterThan(0)
        })
    })

    it('устаревший результат поиска организаций не замещает новый', async () => {
        auth.currentUser = user('SUPER_ADMIN')
        const first = deferred<Awaited<ReturnType<typeof searchOrganizationDirectory>>>()
        vi.mocked(searchOrganizationDirectory)
            .mockImplementationOnce(() => first.promise)
            .mockResolvedValueOnce([{
                id: SECOND_ORG,
                name: 'Second Organization',
                enabled: true,
                type: 'TENANT',
                protected: false,
                version: 1,
            }])
        await loadPage()
        const query = screen.getByPlaceholderText('Название или UUID')
        fireEvent.change(query, { target: { value: 'first' } })
        fireEvent.click(screen.getByRole('button', { name: 'Найти' }))
        await waitFor(() => {
            expect(searchOrganizationDirectory).toHaveBeenCalledTimes(1)
        })
        fireEvent.change(query, { target: { value: 'second' } })
        // Смена запроса отменяет прежний поиск, даже если тот ещё в полёте.
        fireEvent.submit(query.closest('form')!)
        const previousSignal = vi.mocked(searchOrganizationDirectory).mock.calls[0]?.[2]?.signal
        expect(previousSignal?.aborted).toBe(true)
        expect(await screen.findByRole('button', { name: /Second Organization/ }))
            .toBeInTheDocument()
        first.resolve([{
            id: ORG,
            name: 'Stale Organization',
            enabled: true,
            type: 'TENANT',
            protected: false,
            version: 1,
        }])
        await waitFor(() => {
            expect(screen.queryByText('Stale Organization')).not.toBeInTheDocument()
        })
    })

    it('успешная диагностика передаёт идентификатор и AbortSignal', async () => {
        const decision: ModelRouteDecision = {
            id: ROUTE_ID,
            organizationId: ORG,
            userId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
            chatId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
            chatTurnId: null,
            clientRequestId: 'ffffffff-ffff-4fff-8fff-ffffffffffff',
            requestContentHash: 'a'.repeat(64),
            requestedModelKey: null,
            selectedCatalogEntryId: null,
            selectedCatalogVersion: null,
            selectedModelKey: null,
            selectedProvider: null,
            selectedProviderModelId: null,
            policyId: null,
            policyVersion: null,
            requiredCapabilities: [],
            inputAccountingVersion: null,
            additionalInputUnitUpperBound: null,
            estimatedInputTokens: null,
            estimatedOutputTokens: null,
            estimatedMaxCostUsd: null,
            monthlyBudgetUsd: null,
            monthlySpentUsd: null,
            monthlyProjectedUsd: null,
            monthlyCostKnown: false,
            monthlyCostState: 'NOT_EVALUATED',
            budgetEnforcement: null,
            budgetExceeded: false,
            pricingComplete: false,
            outcome: 'DENIED',
            reason: 'MODEL_NOT_FOUND',
            decisionIntegrityVersion: 1,
            decisionSha256: 'b'.repeat(64),
            createdAt: '2026-09-05T00:00:00Z',
        }
        vi.mocked(getModelRouteDecision).mockResolvedValueOnce(decision)
        await loadPage()
        fireEvent.click(screen.getByText('Диагностика маршрутизации'))
        fireEvent.change(screen.getByLabelText('ID решения'), {
            target: { value: ROUTE_ID },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Проверить' }))
        expect(await screen.findByText(`SHA-256: ${'b'.repeat(64)}`))
            .toBeInTheDocument()
        expect(getModelRouteDecision).toHaveBeenCalledWith(ROUTE_ID, {
            signal: expect.any(AbortSignal),
        })
    })

    it('отменяет старую диагностику при редактировании ID и не показывает её результат', async () => {
        const request = deferred<ModelRouteDecision>()
        vi.mocked(getModelRouteDecision).mockReturnValue(request.promise)
        await loadPage()
        fireEvent.click(screen.getByText('Диагностика маршрутизации'))
        const input = screen.getByLabelText('ID решения')
        fireEvent.change(input, { target: { value: ROUTE_ID } })
        fireEvent.click(screen.getByRole('button', { name: 'Проверить' }))
        await waitFor(() => expect(getModelRouteDecision).toHaveBeenCalledTimes(1))
        const signal = vi.mocked(getModelRouteDecision).mock.calls[0]?.[1]?.signal
        fireEvent.change(input, { target: { value: 'другой запрос' } })
        expect(signal?.aborted).toBe(true)
        request.reject(new Error('Ответ старого запроса'))
        await waitFor(() => {
            expect(screen.queryByText('Ответ старого запроса')).not.toBeInTheDocument()
        })
        expect(screen.getByRole('button', { name: 'Проверить' })).not.toBeDisabled()
    })

    it('не публикует частично обновлённые latest/effective после неудачного refresh', async () => {
        auth.currentUser = user('SUPER_ADMIN')
        await loadPage()
        vi.mocked(importRuntimeModelCatalog).mockResolvedValueOnce(
            createModelCatalogEntry({ version: 2 }),
        )
        vi.mocked(getModelCatalog).mockResolvedValueOnce([
            createModelCatalogEntry({ version: 2 }),
        ])
        vi.mocked(getEffectiveModelCatalog).mockRejectedValueOnce(
            new Error('effective failed'),
        )
        fireEvent.click(screen.getByRole('button', { name: 'Добавить Runtime в каталог' }))
        expect(await screen.findByText(/повторно добавлять Runtime не нужно/i))
            .toBeInTheDocument()
        // Обе таблицы остаются на предыдущем согласованном срезе.
        expect(screen.getByText('Последняя сохраненная · v1')).toBeInTheDocument()
        expect(screen.queryByText('Последняя сохраненная · v2')).not.toBeInTheDocument()
    })

    it('показывает результат ошибки POST и позволяет безопасно повторить после завершения', async () => {
        auth.currentUser = user('SUPER_ADMIN')
        vi.mocked(importRuntimeModelCatalog).mockRejectedValueOnce(new Error('POST rejected'))
        await loadPage()
        const button = screen.getByRole('button', { name: 'Добавить Runtime в каталог' })
        fireEvent.click(button)
        expect(await screen.findByText('Не удалось добавить Runtime в каталог.')).toBeInTheDocument()
        await waitFor(() => expect(button).not.toBeDisabled())
        expect(importRuntimeModelCatalog).toHaveBeenCalledTimes(1)
    })

    it('запрещает показывать политику чужого tenant из ответа после сохранения', async () => {
        vi.mocked(createOrganizationModelPolicyVersion).mockResolvedValueOnce(
            createOrganizationModelPolicy({
                configured: true,
                id: ROUTE_ID,
                organizationId: SECOND_ORG,
                version: 1,
                createdByUserId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
                createdAt: '2026-09-05T00:00:00Z',
            }),
        )
        await loadPage()
        fireEvent.click(screen.getByRole('button', { name: 'Изменить правила' }))
        fireEvent.click(screen.getByRole('button', { name: 'Сохранить правила' }))
        expect(await screen.findByText(/Ответ сервера относится к другой организации/))
            .toBeInTheDocument()
        expect(screen.getByText('Не настроены')).toBeInTheDocument()
        expect(screen.queryByRole('dialog', { name: 'Правила использования моделей' }))
            .not.toBeInTheDocument()
    })

    it('SUPER_ADMIN показывает техническую ошибку probe без фальшивого AVAILABLE', async () => {
        auth.currentUser = user('SUPER_ADMIN')
        vi.mocked(probeRuntimeModel).mockRejectedValueOnce(new Error('Probe failed'))
        await loadPage()
        fireEvent.click(screen.getByRole('button', { name: 'Проверить доступность' }))
        expect(await screen.findByText('Не удалось проверить доступность runtime-модели.')).toBeInTheDocument()
        expect(screen.queryByText('Доступна')).not.toBeInTheDocument()
    })

    it('не делает сетевые запросы, если пользователь не авторизован', () => {
        auth.currentUser = null
        render(<AdminModelsPage />)
        expect(getRuntimeModelStatus).not.toHaveBeenCalled()
        expect(getOrganizationModelPolicy).not.toHaveBeenCalled()
        expect(screen.queryByRole('heading', { name: 'Модели и маршрутизация' }))
            .not.toBeInTheDocument()
    })

    it('отменяет первоначальную загрузку и запрос названия организации при unmount', async () => {
        const pending = deferred<ReturnType<typeof createRuntimeModelStatus>>()
        vi.mocked(getRuntimeModelStatus).mockReturnValueOnce(pending.promise)
        const { unmount } = render(<AdminModelsPage />)
        await waitFor(() => expect(getOrganizationDetails).toHaveBeenCalledTimes(1))
        const runtimeSignal = vi.mocked(getRuntimeModelStatus).mock.calls[0]?.[0]
        const organizationSignal = vi.mocked(getOrganizationDetails).mock.calls[0]?.[1]?.signal
        expect(runtimeSignal?.aborted).toBe(false)
        expect(organizationSignal?.aborted).toBe(false)
        unmount()
        expect(runtimeSignal?.aborted).toBe(true)
        expect(organizationSignal?.aborted).toBe(true)
        pending.resolve(createRuntimeModelStatus())
    })

    it('не отправляет второй импорт Runtime, пока первый POST не завершён', async () => {
        auth.currentUser = user('SUPER_ADMIN')
        const pending = deferred<ReturnType<typeof createModelCatalogEntry>>()
        vi.mocked(importRuntimeModelCatalog).mockReturnValueOnce(pending.promise)
        await loadPage()
        const button = screen.getByRole('button', { name: 'Добавить Runtime в каталог' })
        fireEvent.click(button)
        expect(button).toBeDisabled()
        fireEvent.click(button)
        expect(importRuntimeModelCatalog).toHaveBeenCalledTimes(1)
        pending.resolve(createModelCatalogEntry({ version: 2 }))
        await waitFor(() => expect(button).not.toBeDisabled())
        expect(importRuntimeModelCatalog).toHaveBeenCalledTimes(1)
    })

})
