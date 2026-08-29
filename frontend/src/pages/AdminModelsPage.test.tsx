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
} from '../api/authApi'
import {
    getEffectiveModelCatalog,
    getModelCatalog,
    getModelRouteDecision,
    getOrganizationModelPolicy,
    getRuntimeModelStatus,
    importRuntimeModelCatalog,
} from '../api/modelApi'
import AdminModelsPage from './AdminModelsPage'

const authMock = vi.hoisted(() => ({
    currentUser: null as AuthUser | null,
}))

vi.mock('../auth/useAuth', () => ({
    useAuth: () => authMock,
}))

vi.mock('../api/modelApi', async (importOriginal) => {
    const actual =
        await importOriginal<typeof import('../api/modelApi')>()

    return {
        ...actual,
        getRuntimeModelStatus: vi.fn(),
        getModelCatalog: vi.fn(),
        getEffectiveModelCatalog: vi.fn(),
        getOrganizationModelPolicy: vi.fn(),
        createModelCatalogVersion: vi.fn(),
        importRuntimeModelCatalog: vi.fn(),
        createOrganizationModelPolicyVersion: vi.fn(),
        getModelRouteDecision: vi.fn(),
    }
})

vi.mock('../api/organizationApi', async (importOriginal) => {
    const actual =
        await importOriginal<typeof import('../api/organizationApi')>()

    return {
        ...actual,
        searchOrganizationDirectory: vi.fn(),
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
        enabled: true,
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

describe('AdminModelsPage', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        stubLoadedState()
    })

    it('ADMIN видит подключённую модель и правила, но не может менять глобальный каталог', async () => {
        authMock.currentUser =
            currentUser('ADMIN')

        render(<AdminModelsPage />)

        expect(
            await screen.findByText(
                'Модели и маршрутизация',
            ),
        ).toBeInTheDocument()

        expect(
            screen.getAllByText(
                /mock-safeai/,
            ).length,
        ).toBeGreaterThan(0)

        expect(
            screen.getByRole(
                'button',
                {name: 'Настроить правила'},
            ),
        ).toBeInTheDocument()

        expect(
            screen.queryByRole(
                'button',
                {name: 'Добавить подключённую модель'},
            ),
        ).not.toBeInTheDocument()
    })


    it('показывает рабочую страницу при пустом каталоге и ненастроенных правилах', async () => {
        authMock.currentUser =
            currentUser('ADMIN')

        catalogMock.mockResolvedValueOnce([])
        effectiveCatalogMock.mockResolvedValueOnce([])

        render(<AdminModelsPage />)

        expect(
            await screen.findByText(
                'Модели и маршрутизация',
            ),
        ).toBeInTheDocument()

        expect(
            screen.getByText(
                'Каталог моделей пока пуст',
            ),
        ).toBeInTheDocument()

        expect(
            screen.getByText(
                'Не настроены',
            ),
        ).toBeInTheDocument()

        expect(
            screen.queryByText(
                'Не удалось загрузить данные',
            ),
        ).not.toBeInTheDocument()
    })

    it('SUPER_ADMIN получает управление каталогом', async () => {
        authMock.currentUser =
            currentUser('SUPER_ADMIN')

        render(<AdminModelsPage />)

        const importButton =
            await screen.findByRole(
                'button',
                {name: 'Добавить подключённую модель'},
            )

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

        fireEvent.click(importButton)

        await waitFor(() => {
            expect(
                importRuntimeMock,
            ).toHaveBeenCalledTimes(1)
        })
    })

    it('нижнюю область каталога можно почти полностью опустить вниз', async () => {
        authMock.currentUser =
            currentUser('ADMIN')

        render(<AdminModelsPage />)

        await screen.findByText(
            'Модели и маршрутизация',
        )

        const separator =
            screen.getByRole(
                'separator',
                {
                    name: 'Изменить размер области: каталог моделей',
                },
            )

        expect(separator)
            .toHaveAttribute(
                'aria-valuemin',
                '64',
            )
    })

    it('диагностика маршрутизации проверяет UUID до запроса к backend', async () => {
        authMock.currentUser =
            currentUser('ADMIN')

        render(<AdminModelsPage />)

        const summary =
            await screen.findByText(
                'Диагностика маршрутизации',
            )

        fireEvent.click(summary)

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
                'Введите корректный ID решения (UUID).',
            ),
        ).toBeInTheDocument()

        expect(
            routeDecisionMock,
        ).not.toHaveBeenCalled()
    })
})

