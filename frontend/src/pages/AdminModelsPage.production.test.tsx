// frontend/src/pages/AdminModelsPage.production.test.tsx
// ============================================================
// frontend/src/pages/AdminModelsPage.production.test.tsx
// ============================================================
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../features/auth/api/authApi'
import type { ModelRouteDecision, OrganizationModelPolicy } from '../entities/model/api/modelApi'
import {
    createOrganizationModelPolicyVersion,
    getEffectiveModelCatalog,
    getModelCatalog,
    getModelRouteDecision,
    getOrganizationModelPolicy,
    getRuntimeModelStatus,
    importRuntimeModelCatalog,
    probeRuntimeModel,
} from '../entities/model/api/modelApi'
import { getOrganizationDetails, searchOrganizationDirectory } from '../entities/organization/api/organizationApi'
import {
    createModelCatalogEntry,
    createOrganizationModelPolicy,
    createRuntimeModelStatus,
} from '../test/fixtures/modelControlPlaneTestFixtures'
import AdminModelsPage from './AdminModelsPage'
import { MemoryRouter } from 'react-router-dom'
import { ApiError } from '../shared/api/http'

const auth = vi.hoisted(() => ({ currentUser: null as AuthUser | null }))
vi.mock('../features/auth/state/useAuth', () => ({ useAuth: () => auth }))
vi.mock('../entities/model/api/modelApi', async (importOriginal) => ({
    ...await importOriginal<typeof import('../entities/model/api/modelApi')>(),
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
vi.mock('../entities/organization/api/organizationApi', async (importOriginal) => ({
    ...await importOriginal<typeof import('../entities/organization/api/organizationApi')>(),
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

function configureCatalogWithoutRuntime() {
    const entry = createModelCatalogEntry({modelKey: 'mock:other', providerModelId: 'other', displayName: 'Other model'})
    vi.mocked(getModelCatalog).mockResolvedValue([entry])
    vi.mocked(getEffectiveModelCatalog).mockResolvedValue([entry])
}

async function loadPage() {
    render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)
    await screen.findByRole('heading', { name: 'Управление AI-моделями' })
}

describe('AdminModelsPage — контрактные и регрессионные сценарии', () => {
    beforeEach(() => {
        vi.resetAllMocks()
        auth.currentUser = user('ADMIN')
        configureApi()
    })

    it('показывает ошибку начальной загрузки, а не частично сформированную страницу', async () => {
        vi.mocked(getRuntimeModelStatus).mockRejectedValueOnce(new Error('runtime unavailable'))
        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)
        expect(await screen.findByText('Не удалось загрузить настройки моделей.')).toBeInTheDocument()
        expect(screen.queryByRole('heading', { name: 'Управление AI-моделями' }))
            .not.toBeInTheDocument()
    })

    it('не отображает политику другой организации, даже при некорректном успешном GET', async () => {
        vi.mocked(getOrganizationModelPolicy).mockResolvedValueOnce(
            createOrganizationModelPolicy({ organizationId: SECOND_ORG }),
        )
        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)
        expect(await screen.findByText(/Сервер вернул правила другой организации/))
            .toBeInTheDocument()
        expect(screen.queryByRole('heading', { name: 'Управление AI-моделями' }))
            .not.toBeInTheDocument()
    })

    it('не угадывает модель при совпадении идентификатора разных провайдеров', async () => {
        auth.currentUser = user('SUPER_ADMIN')
        const entries = [createModelCatalogEntry({modelKey:'one:shared', providerModelId:'shared'}),
            createModelCatalogEntry({modelKey:'two:shared', providerModelId:'shared', id: SECOND_ORG})]
        vi.mocked(getModelCatalog).mockResolvedValue(entries)
        vi.mocked(getEffectiveModelCatalog).mockResolvedValue(entries)
        render(<MemoryRouter initialEntries={['/admin/models?section=catalog&usageModel=shared']}><AdminModelsPage /></MemoryRouter>)
        expect(await screen.findByText(/Найдено несколько моделей/)).toBeInTheDocument()
        expect(screen.queryByRole('dialog')).toBeNull()
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
        fireEvent.click(screen.getByRole('tab', { name: 'Доступ' }))
        fireEvent.change(screen.getByRole('combobox', {name: 'Управляемая организация'}), {
            target: { value: 'Second' },
        })
        fireEvent.click(await screen.findByRole('option', { name: /Second Organization/ }))

        expect(screen.queryByRole('heading', { name: 'Управление AI-моделями' }))
            .not.toBeInTheDocument()
        expect(screen.getByText('Загрузка настроек моделей...')).toBeInTheDocument()

        secondPolicy.resolve(createOrganizationModelPolicy({ organizationId: SECOND_ORG }))
        await screen.findByRole('heading', { name: 'Управление AI-моделями' })
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
        fireEvent.click(screen.getByRole('tab', { name: 'Доступ' }))
        const query = screen.getByRole('combobox', {name: 'Управляемая организация'})
        fireEvent.change(query, { target: { value: 'first' } })
        await waitFor(() => {
            expect(searchOrganizationDirectory).toHaveBeenCalledTimes(1)
        })
        fireEvent.change(query, { target: { value: 'second' } })
        // Смена запроса отменяет прежний поиск, даже если тот ещё в полёте.
        const previousSignal = vi.mocked(searchOrganizationDirectory).mock.calls[0]?.[2]?.signal
        expect(previousSignal?.aborted).toBe(true)
        expect(await screen.findByRole('option', { name: /Second Organization/ }))
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
        fireEvent.click(screen.getByRole('tab', { name: 'Диагностика' }))
        fireEvent.change(screen.getByLabelText('Идентификатор решения безопасности (UUID)'), {
            target: { value: ROUTE_ID },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Найти решение' }))
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
        fireEvent.click(screen.getByRole('tab', { name: 'Диагностика' }))
        const input = screen.getByLabelText('Идентификатор решения безопасности (UUID)')
        fireEvent.change(input, { target: { value: ROUTE_ID } })
        fireEvent.click(screen.getByRole('button', { name: 'Найти решение' }))
        await waitFor(() => expect(getModelRouteDecision).toHaveBeenCalledTimes(1))
        const signal = vi.mocked(getModelRouteDecision).mock.calls[0]?.[1]?.signal
        fireEvent.change(input, { target: { value: 'другой запрос' } })
        expect(signal?.aborted).toBe(true)
        request.reject(new Error('Ответ старого запроса'))
        await waitFor(() => {
            expect(screen.queryByText('Ответ старого запроса')).not.toBeInTheDocument()
        })
        expect(screen.getByRole('button', { name: 'Найти решение' })).not.toBeDisabled()
    })

    it('открывает диагностику из журнала и проверяет именно номер решения', async () => {
        vi.mocked(getModelRouteDecision).mockRejectedValue(new Error('not found'))
        render(<MemoryRouter initialEntries={['/admin/models?section=diagnostics&decisionId=' + ROUTE_ID]}>
            <AdminModelsPage />
        </MemoryRouter>)
        await waitFor(() => expect(getModelRouteDecision).toHaveBeenCalledWith(ROUTE_ID, { signal: expect.any(AbortSignal) }))
        expect(screen.getByLabelText('Идентификатор решения безопасности (UUID)')).toHaveValue(ROUTE_ID)
    })

    it('не публикует частично обновлённые latest/effective после неудачного refresh', async () => {
        auth.currentUser = user('SUPER_ADMIN')
        configureCatalogWithoutRuntime()
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
        // The import action is located on the Catalog tab, not Overview.
        fireEvent.click(screen.getByRole('tab', { name: 'Каталог' }))
        fireEvent.click(screen.getByRole('button', { name: 'Добавить подключённую модель в каталог' }))
        expect(await screen.findByText(/повторно добавлять модель не нужно/i))
            .toBeInTheDocument()
        // Обе таблицы остаются на предыдущем согласованном срезе.
        expect(screen.getByText('Последняя и действующая · v1')).toBeInTheDocument()
        expect(screen.queryByText('Последняя и действующая · v2')).not.toBeInTheDocument()
    })

    it('показывает результат ошибки POST и позволяет безопасно повторить после завершения', async () => {
        auth.currentUser = user('SUPER_ADMIN')
        configureCatalogWithoutRuntime()
        vi.mocked(importRuntimeModelCatalog).mockRejectedValueOnce(new Error('POST rejected'))
        await loadPage()
        fireEvent.click(screen.getByRole('tab', { name: 'Каталог' }))
        const button = screen.getByRole('button', { name: 'Добавить подключённую модель в каталог' })
        fireEvent.click(button)
        expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось добавить подключённую модель в каталог')
        // Ошибка POST не должна скрывать согласованную ранее таблицу и версию.
        expect(screen.getByRole('region', { name: 'Таблица каталога моделей' })).toBeInTheDocument()
        expect(screen.getByRole('tab', { name: 'Каталог' })).toHaveAttribute('aria-selected', 'true')
        await waitFor(() => expect(button).not.toBeDisabled())
        expect(importRuntimeModelCatalog).toHaveBeenCalledTimes(1)
    })

    it.each(['MANUAL', 'MIGRATED', 'RUNTIME_IMPORT'] as const)(
        'не предлагает повторный импорт существующей версии %s',
        async source => {
            auth.currentUser = user('SUPER_ADMIN')
            const entry = createModelCatalogEntry({ source, version: 2 })
            vi.mocked(getModelCatalog).mockResolvedValue([entry])
            // Даже если версия ещё не вступила в силу, ключ уже занят.
            vi.mocked(getEffectiveModelCatalog).mockResolvedValue([])
            await loadPage()
            fireEvent.click(screen.getByRole('tab', { name: 'Каталог' }))
            expect(screen.getByText('Подключённая модель уже в каталоге')).toBeInTheDocument()
            expect(screen.queryByRole('button', {name: 'Подключённая модель уже в каталоге'})).not.toBeInTheDocument()
            expect(screen.queryByRole('button', {name: 'Добавить подключённую модель в каталог'})).not.toBeInTheDocument()
            expect(importRuntimeModelCatalog).not.toHaveBeenCalled()
            expect(screen.getByRole('button', {name: 'Новая версия'})).toBeEnabled()
        },
    )

    it('перезагружает каталог при конкурентном добавлении, не показывает технический конфликт', async () => {
        auth.currentUser = user('SUPER_ADMIN')
        configureCatalogWithoutRuntime()
        await loadPage()
        const registered = createModelCatalogEntry({source: 'MANUAL', version: 2})
        vi.mocked(importRuntimeModelCatalog).mockRejectedValueOnce(
            new ApiError('source=MANUAL, version=2; import-runtime governance', {code: 'CONFLICT'}, 409),
        )
        vi.mocked(getModelCatalog).mockResolvedValueOnce([registered])
        vi.mocked(getEffectiveModelCatalog).mockResolvedValueOnce([registered])
        fireEvent.click(screen.getByRole('tab', { name: 'Каталог' }))
        fireEvent.click(screen.getByRole('button', {name: 'Добавить подключённую модель в каталог'}))
        expect(await screen.findByText('Подключённая модель уже в каталоге')).toBeInTheDocument()
        expect(screen.queryByRole('button', {name: 'Добавить подключённую модель в каталог'})).not.toBeInTheDocument()
        expect(screen.queryByText(/source=MANUAL|governance/)).not.toBeInTheDocument()
        expect(screen.queryByRole('alert')).not.toBeInTheDocument()
        expect(importRuntimeModelCatalog).toHaveBeenCalledTimes(1)
    })

    it('блокирует добавление отключённой модели даже при пустом каталоге', async () => {
        auth.currentUser = user('SUPER_ADMIN')
        vi.mocked(getRuntimeModelStatus).mockResolvedValue(createRuntimeModelStatus({enabled: false}))
        vi.mocked(getModelCatalog).mockResolvedValue([])
        vi.mocked(getEffectiveModelCatalog).mockResolvedValue([])
        await loadPage()
        fireEvent.click(screen.getByRole('tab', {name: 'Каталог'}))
        const button = screen.getByRole('button', {name: 'Добавить подключённую модель в каталог'})
        expect(button).toBeDisabled()
        expect(screen.getByText(/Подключённая модель отключена в настройках сервера/)).toBeInTheDocument()
        fireEvent.click(button)
        expect(importRuntimeModelCatalog).not.toHaveBeenCalled()
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
        fireEvent.click(screen.getByRole('tab', { name: 'Доступ' }))
        fireEvent.click(screen.getByRole('button', { name: 'Настроить правила' }))
        fireEvent.click(screen.getByRole('button', { name: 'Сохранить правила' }))
        expect(await screen.findByText(/Ответ сервера относится к другой организации/))
            .toBeInTheDocument()
        expect(screen.getByText('Не настроены')).toBeInTheDocument()
        expect(screen.queryByRole('dialog', { name: 'Настройка правил использования моделей' }))
            .not.toBeInTheDocument()
    })

    it('SUPER_ADMIN показывает техническую ошибку probe без фальшивого AVAILABLE', async () => {
        auth.currentUser = user('SUPER_ADMIN')
        vi.mocked(probeRuntimeModel).mockRejectedValueOnce(new Error('Probe failed'))
        await loadPage()
        fireEvent.click(screen.getByRole('tab', { name: 'Диагностика' }))
        fireEvent.click(screen.getByRole('button', { name: 'Проверить доступность' }))
        expect(await screen.findByText('Не удалось проверить доступность подключённой модели.')).toBeInTheDocument()
        expect(screen.queryByText('Probe failed')).not.toBeInTheDocument()
        expect(screen.queryByText('Доступна')).not.toBeInTheDocument()
    })

    it('не делает сетевые запросы, если пользователь не авторизован', () => {
        auth.currentUser = null
        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)
        expect(getRuntimeModelStatus).not.toHaveBeenCalled()
        expect(getOrganizationModelPolicy).not.toHaveBeenCalled()
        expect(screen.queryByRole('heading', { name: 'Управление AI-моделями' }))
            .not.toBeInTheDocument()
    })

    it('отменяет первоначальную загрузку и запрос названия организации при unmount', async () => {
        const pending = deferred<ReturnType<typeof createRuntimeModelStatus>>()
        vi.mocked(getRuntimeModelStatus).mockReturnValueOnce(pending.promise)
        const { unmount } = render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)
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
        configureCatalogWithoutRuntime()
        const pending = deferred<ReturnType<typeof createModelCatalogEntry>>()
        vi.mocked(importRuntimeModelCatalog).mockReturnValueOnce(pending.promise)
        await loadPage()
        fireEvent.click(screen.getByRole('tab', { name: 'Каталог' }))
        const button = screen.getByRole('button', { name: 'Добавить подключённую модель в каталог' })
        fireEvent.click(button)
        expect(button).toBeDisabled()
        fireEvent.click(button)
        expect(importRuntimeModelCatalog).toHaveBeenCalledTimes(1)
        pending.resolve(createModelCatalogEntry({ version: 2 }))
        await waitFor(() => expect(button).not.toBeDisabled())
        expect(importRuntimeModelCatalog).toHaveBeenCalledTimes(1)
    })

})
