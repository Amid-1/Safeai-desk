// ============================================================
// frontend/src/pages/AdminModelsPage.test.tsx
// Тесты навигации, прав доступа, каталога и диагностики.
// ============================================================
import {
    fireEvent,
    render,
    screen,
    waitFor,
} from '@testing-library/react'
import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import type {
    AuthUser,
} from '../features/auth/api/authApi'
import {
    getEffectiveModelCatalog,
    getModelCatalog,
    getModelRouteDecision,
    getOrganizationModelPolicy,
    getRuntimeModelStatus,
    importRuntimeModelCatalog,
    probeRuntimeModel,
} from '../entities/model/api/modelApi'
import {
    getOrganizationDetails,
} from '../entities/organization/api/organizationApi'
import AdminModelsPage from './AdminModelsPage'
import { MemoryRouter } from 'react-router-dom'

const authMock = vi.hoisted(() => ({
    currentUser: null as AuthUser | null,
}))

vi.mock('../features/auth/state/useAuth', () => ({
    useAuth: () => authMock,
}))

vi.mock('../entities/model/api/modelApi', async (importOriginal) => {
    const actual =
        await importOriginal<typeof import('../entities/model/api/modelApi')>()

    return {
        ...actual,
        getRuntimeModelStatus: vi.fn(),
        getModelCatalog: vi.fn(),
        getEffectiveModelCatalog: vi.fn(),
        getOrganizationModelPolicy: vi.fn(),
        createModelCatalogVersion: vi.fn(),
        importRuntimeModelCatalog: vi.fn(),
        probeRuntimeModel: vi.fn(),
        createOrganizationModelPolicyVersion: vi.fn(),
        getModelRouteDecision: vi.fn(),
        previewOrganizationModelPolicy: vi.fn(),
    }
})

vi.mock('../entities/organization/api/organizationApi', async (importOriginal) => {
    const actual =
        await importOriginal<typeof import('../entities/organization/api/organizationApi')>()

    return {
        ...actual,
        searchOrganizationDirectory: vi.fn(),
        getOrganizationDetails: vi.fn(),
    }
})

const runtimeMock =
    vi.mocked(getRuntimeModelStatus)

const catalogMock =
    vi.mocked(getModelCatalog)

const effectiveCatalogMock =
    vi.mocked(getEffectiveModelCatalog)

const policyMock =
    vi.mocked(getOrganizationModelPolicy)

const importRuntimeMock =
    vi.mocked(importRuntimeModelCatalog)

const routeDecisionMock =
    vi.mocked(getModelRouteDecision)

const runtimeProbeMock =
    vi.mocked(probeRuntimeModel)


const organizationDetailsMock =
    vi.mocked(getOrganizationDetails)

const ORGANIZATION_ID =
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

const USER_ID =
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

const CATALOG_ENTRY = {
    id: '11111111-1111-4111-8111-111111111111',
    modelKey: 'mock:mock-safeai',
    version: 1,
    provider: 'mock',
    providerModelId: 'mock-safeai',
    displayName: 'mock-safeai',
    lifecycle: 'ACTIVE' as const,
    maxInputTokens: 64000,
    maxOutputTokens: 2048,
    capabilities: [],
    inputModalities: ['TEXT' as const],
    outputModalities: ['TEXT' as const],
    retentionStatus: 'NOT_DECLARED' as const,
    retentionDays: null,
    trainingUseStatus: 'NOT_DECLARED' as const,
    pricingStatus: 'FREE' as const,
    pricingComplete: true,
    inputUsdPer1mTokens: '0',
    cachedInputUsdPer1mTokens: '0',
    cacheWriteInputUsdPer1mTokens: '0',
    outputUsdPer1mTokens: '0',
    extraPricingJson: '{}',
    pricingVersion: 'mock-2026-01',
    effectiveFrom: '2026-08-28T10:00:00Z',
    source: 'RUNTIME_IMPORT' as const,
    createdByUserId: USER_ID,
    createdAt: '2026-08-28T10:00:00Z',
}

function currentUser(
    role: 'ADMIN' | 'SUPER_ADMIN',
): AuthUser {
    return {
        id: USER_ID,
        organizationId: ORGANIZATION_ID,
        email: 'admin@safeai.test',
        fullName: 'Admin',
        enabled: true,
        roles: [role],
    }
}

function stubLoadedState() {
    organizationDetailsMock.mockResolvedValue({
        id: ORGANIZATION_ID,
        name: 'Demo Organization',
    } as Awaited<ReturnType<typeof getOrganizationDetails>>)

    runtimeMock.mockResolvedValue({
        provider: 'mock',
        model: 'mock-safeai',
        enabled: true,
        routingMode: 'SINGLE_PROVIDER_STATIC',
        maxInputTokens: 64000,
        maxOutputTokens: 2048,
        toolsSupported: false,
        visionSupported: false,
        structuredOutputSupported: false,
        dataRetentionStatus: 'NOT_DECLARED',
        healthStatus: 'NOT_PROBED',
        pricingStatus: 'FREE',
        inputUsdPer1mTokens: '0',
        outputUsdPer1mTokens: '0',
        pricingVersion: 'mock-2026-01',
    })

    catalogMock.mockResolvedValue([
        CATALOG_ENTRY,
    ])

    effectiveCatalogMock.mockResolvedValue([
        CATALOG_ENTRY,
    ])

    policyMock.mockResolvedValue({
        configured: false,
        id: null,
        organizationId: ORGANIZATION_ID,
        version: 0,
        enabled: false,
        allowModelKeys: [],
        denyModelKeys: [],
        defaultModelKey: null,
        maxInputTokens: null,
        maxOutputTokens: null,
        maxRequestCostUsd: null,
        monthlyBudgetUsd: null,
        budgetEnforcement: 'SOFT',
        requireCompletePricing: false,
        requireNoTraining: false,
        requireZeroDataRetention: false,
        createdByUserId: null,
        createdAt: null,
    })
}

// Общие действия тестов: реальный компонент и MemoryRouter,
// без мокирования бизнес-контроллера или модальных окон.
async function openCatalogAsSuperAdmin(): Promise<HTMLElement> {
    authMock.currentUser = currentUser('SUPER_ADMIN')

    render(
        <MemoryRouter initialEntries={['/admin/models']}>
            <AdminModelsPage />
        </MemoryRouter>,
    )

    const catalogTab = await screen.findByRole('tab', { name: 'Каталог' })
    fireEvent.click(catalogTab)

    return screen.findByRole('button', {
        name: 'Добавить подключённую модель в каталог',
    })
}

describe('AdminModelsPage', () => {
    beforeEach(() => {
        vi.resetAllMocks()
        authMock.currentUser = null
        stubLoadedState()
    })

    it('открывает каталог напрямую по URL и переключает разделы без нового GET', async () => {
        authMock.currentUser = currentUser('SUPER_ADMIN')
        render(<MemoryRouter initialEntries={['/admin/models?section=catalog']}><AdminModelsPage /></MemoryRouter>)
        await screen.findByRole('heading', { name: 'Каталог моделей' })
        expect(screen.getByRole('tab', { name: 'Каталог' })).toHaveAttribute('aria-selected', 'true')
        expect(screen.getByRole('region', { name: 'Таблица каталога моделей' })).toBeInTheDocument()
        fireEvent.click(screen.getByRole('tab', { name: 'Доступ' }))
        expect(screen.getByRole('heading', { name: 'Доступ организаций' })).toBeInTheDocument()
        expect(screen.queryByRole('region', { name: 'Таблица каталога моделей' })).not.toBeInTheDocument()
        expect(runtimeMock).toHaveBeenCalledTimes(1)
        expect(policyMock).toHaveBeenCalledTimes(1)
    })

    it('ADMIN видит подключённую модель и правила, но не может менять глобальный каталог', async () => {
        authMock.currentUser =
            currentUser('ADMIN')

        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)

        expect(
            await screen.findByText(
                'Модели и доступ',
            ),
        ).toBeInTheDocument()

        expect(
            screen.getAllByText(
                /mock-safeai/,
            ).length,
        ).toBeGreaterThan(0)

        fireEvent.click(screen.getByRole('tab', { name: 'Каталог' }))

        expect(
            screen.queryByRole(
                'button',
                {name: 'Добавить подключённую модель в каталог'},
            ),
        ).not.toBeInTheDocument()

        expect(screen.getByText('Каталог изменяет SUPER_ADMIN.'))
            .toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', {name: 'Создать модель'}))
        expect(screen.getByText(/Создание и изменение версий каталога доступно только SUPER_ADMIN/))
            .toHaveAttribute('role', 'status')
        expect(screen.queryByRole('dialog', {name: 'Модель каталога'}))
            .not.toBeInTheDocument()
        fireEvent.click(screen.getByRole('tab', { name: 'Обзор' }))
        fireEvent.click(screen.getByText(/Предварительная проверка совместимости/))
        expect(screen.getByRole('heading', {name: 'Проверка доступности модели'})).toBeInTheDocument()
        expect(screen.getByText('Нужна проверка запроса')).toBeInTheDocument()

        expect(
            screen.queryByRole(
                'button',
                {name: 'Проверить доступность'},
            ),
        ).not.toBeInTheDocument()
    })

    it('передаёт AbortSignal во все первоначальные GET-запросы', async () => {
        authMock.currentUser =
            currentUser('ADMIN')

        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)

        await screen.findByText(
            'Модели и доступ',
        )

        expect(runtimeMock)
            .toHaveBeenCalledWith(
                expect.any(AbortSignal),
            )

        expect(catalogMock)
            .toHaveBeenCalledWith({
                signal: expect.any(AbortSignal),
            })

        expect(effectiveCatalogMock)
            .toHaveBeenCalledWith({
                signal: expect.any(AbortSignal),
            })

        expect(policyMock)
            .toHaveBeenCalledWith(
                ORGANIZATION_ID,
                {
                    signal: expect.any(AbortSignal),
                },
            )
    })

    it('показывает рабочую страницу при пустом каталоге и ненастроенных правилах', async () => {
        authMock.currentUser =
            currentUser('ADMIN')

        catalogMock.mockResolvedValueOnce([])
        effectiveCatalogMock.mockResolvedValueOnce([])

        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)

        expect(
            await screen.findByText(
                'Модели и доступ',
            ),
        ).toBeInTheDocument()

        expect(screen.getAllByText('Не настроены').length).toBeGreaterThan(0)
        fireEvent.click(screen.getByRole('tab', { name: 'Каталог' }))
        expect(screen.getByText('Каталог моделей пока пуст')).toBeInTheDocument()

        expect(
            screen.queryByText(
                'Не удалось загрузить данные',
            ),
        ).not.toBeInTheDocument()
    })

    it('SUPER_ADMIN получает управление каталогом', async () => {
        const importButton = await openCatalogAsSuperAdmin()

        importRuntimeMock.mockResolvedValueOnce({
            ...CATALOG_ENTRY,
            version: 2,
        })

        catalogMock.mockResolvedValueOnce([
            {
                ...CATALOG_ENTRY,
                version: 2,
            },
        ])

        effectiveCatalogMock.mockResolvedValueOnce([
            {
                ...CATALOG_ENTRY,
                version: 2,
            },
        ])

        expect(screen.queryByText('Каталог изменяет SUPER_ADMIN.'))
            .not.toBeInTheDocument()
        expect(screen.getByRole('button', {name: 'Создать модель'}))
            .toBeInTheDocument()
        fireEvent.click(importButton)

        await waitFor(() => {
            expect(
                importRuntimeMock,
            ).toHaveBeenCalledTimes(1)
        })
    })

    it('SUPER_ADMIN открывает создание каталожной версии из закрепленной верхней панели', async () => {
        authMock.currentUser = currentUser('SUPER_ADMIN')
        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)
        fireEvent.click(await screen.findByRole('tab', { name: 'Каталог' }))
        fireEvent.click(screen.getByRole('button', {name: 'Создать модель'}))
        // The runtime key already exists in the catalog: the initial draft
        // must be an immutable new version, not a duplicate first version.
        expect(screen.getByRole('dialog', {name: 'Новая версия: mock-safeai'}))
            .toBeInTheDocument()
        // Selecting a genuinely new key switches to first-version creation.
        fireEvent.change(screen.getByRole('textbox', {name: /Ключ модели/}), {
            target: {value: 'vendor:new-model'},
        })
        expect(screen.getByRole('dialog', {name: 'Добавление модели в каталог'}))
            .toBeInTheDocument()
        const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
        try {
            fireEvent.click(screen.getByRole('button', {name: 'Закрыть окно'}))
            expect(screen.queryByRole('dialog', {name: 'Добавление модели в каталог'}))
                .not.toBeInTheDocument()
            expect(confirm).toHaveBeenCalled()
        } finally {
            confirm.mockRestore()
        }
    })

    it('не сообщает об ошибке mutation, если Runtime уже сохранён, а refresh упал', async () => {
        const importButton = await openCatalogAsSuperAdmin()

        importRuntimeMock.mockResolvedValueOnce({
            ...CATALOG_ENTRY,
            version: 2,
        })
        catalogMock.mockRejectedValueOnce(
            new Error('refresh failed'),
        )
        effectiveCatalogMock.mockResolvedValueOnce([
            CATALOG_ENTRY,
        ])

        fireEvent.click(importButton)

        expect(
            await screen.findByText(
                /Подключённая модель сохранена в каталоге:.*повторно добавлять модель не нужно/i,
            ),
        ).toBeInTheDocument()

        expect(
            screen.queryByText(
                'Не удалось добавить подключённую модель в каталог.',
            ),
        ).not.toBeInTheDocument()
    })

    it('SUPER_ADMIN может запустить metadata-only runtime probe', async () => {
        authMock.currentUser =
            currentUser('SUPER_ADMIN')

        runtimeProbeMock.mockResolvedValueOnce({
            provider: 'mock',
            model: 'mock-safeai',
            status: 'AVAILABLE',
            checkedAt: '2026-09-05T18:00:00Z',
            latencyMs: 0,
            httpStatus: null,
            message: 'Локальный mock provider доступен',
        })

        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)

        await screen.findByRole('tab', { name: 'Диагностика' })
        fireEvent.click(screen.getByRole('tab', { name: 'Диагностика' }))
        const button = screen.getByRole('button', {name: 'Проверить доступность'})

        fireEvent.click(button)

        await waitFor(() => {
            expect(runtimeProbeMock)
                .toHaveBeenCalledTimes(1)
        })

        expect(
            await screen.findByText(
                'Локальный mock provider доступен',
            ),
        ).toBeInTheDocument()
    })

    it('каталог предоставляет собственный viewport и доступную ручку высоты без перекрытия остальной страницы', async () => {
        authMock.currentUser = currentUser('ADMIN')
        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)
        await screen.findByRole('tab', { name: 'Каталог' })
        fireEvent.click(screen.getByRole('tab', { name: 'Каталог' }))
        expect(screen.getByRole('region', { name: 'Таблица каталога моделей' })).toBeInTheDocument()
        expect(screen.getByRole('separator', { name: 'Изменить высоту таблицы каталога' })).toBeInTheDocument()
    })

    it('показывает окно правил по явной кнопке и закрывает его крестиком', async () => {
        authMock.currentUser = currentUser('ADMIN')
        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)
        await screen.findByRole('tab', { name: 'Доступ' })
        fireEvent.click(screen.getByRole('tab', { name: 'Доступ' }))
        fireEvent.click(screen.getByRole('button', {name: 'Изменить правила'}))
        expect(screen.getByRole('dialog', {name: 'Правила использования моделей'}))
            .toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', {name: 'Закрыть окно'}))
        expect(screen.queryByRole('dialog', {name: 'Правила использования моделей'}))
            .not.toBeInTheDocument()
    })

    it('диагностика маршрутизации проверяет UUID до запроса к backend', async () => {
        authMock.currentUser =
            currentUser('ADMIN')

        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)

        await screen.findByRole('tab', { name: 'Диагностика' })
        fireEvent.click(screen.getByRole('tab', { name: 'Диагностика' }))

        fireEvent.change(
            screen.getByLabelText(
                'ID решения',
            ),
            {
                target: {
                    value: 'bad-id',
                },
            },
        )

        fireEvent.click(
            screen.getByRole(
                'button',
                {name: 'Проверить'},
            ),
        )

        expect(
            await screen.findByText(
                'Некорректный идентификатор решения. Укажите UUID из журнала аудита.',
            ),
        ).toBeInTheDocument()

        expect(
            routeDecisionMock,
        ).not.toHaveBeenCalled()
    })
})




