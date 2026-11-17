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
    previewOrganizationModelPolicy,
    createOrganizationModelPolicyVersion,
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
import { getModelCatalogHistory } from '../entities/model/api/modelCatalogHistoryApi'

vi.mock('../entities/model/api/modelCatalogHistoryApi', () => ({ getModelCatalogHistory: vi.fn() }))

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
        expect(screen.getByRole('heading', { name: 'Правила использования моделей' })).toBeInTheDocument()
        expect(screen.queryByRole('region', { name: 'Таблица каталога моделей' })).not.toBeInTheDocument()
        expect(runtimeMock).toHaveBeenCalledTimes(1)
        expect(policyMock).toHaveBeenCalledTimes(1)
    })

    it('карточки обзора доступны ADMIN и переводят в каталог без нового запроса', async () => {
        authMock.currentUser = currentUser('ADMIN')
        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)
        fireEvent.click(await screen.findByRole('button', { name: /Каталог моделей.*1 модель/ }))
        expect(screen.getByRole('tab', { name: 'Каталог' })).toHaveAttribute('aria-selected', 'true')
        expect(screen.queryByRole('button', { name: 'Создать модель' })).not.toBeInTheDocument()
        expect(catalogMock).toHaveBeenCalledTimes(1)
    })

    it.each(['ADMIN', 'SUPER_ADMIN'] as const)('для %s карточки ведут к разным целям без probe', async (role) => {
        authMock.currentUser = currentUser(role)
        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)
        fireEvent.click(await screen.findByRole('button', {name: /Подключение модели/}))
        expect(document.getElementById('models-runtime-connection')).toHaveFocus()
        fireEvent.click(screen.getByRole('tab', {name: 'Обзор'}))
        fireEvent.click(screen.getByRole('button', {name: /Проверка соединения/}))
        expect(document.getElementById('models-runtime-probe')).toHaveFocus()
        expect(runtimeProbeMock).not.toHaveBeenCalled()
        if (role === 'ADMIN') expect(screen.getByText('Проверку запускает суперадминистратор.')).toBeVisible()
        else expect(screen.getByRole('button', {name: 'Проверить доступность'})).toBeVisible()
        fireEvent.click(screen.getByRole('tab', {name: 'Обзор'}))
        fireEvent.click(screen.getByRole('button', {name: /Правила доступа/}))
        expect(screen.getByRole('tab', {name: 'Доступ'})).toHaveAttribute('aria-selected', 'true')
    })

    it.each(['ADMIN', 'SUPER_ADMIN'] as const)('из диагностики для %s открывает точную версию без смены вкладки', async (role) => {
        authMock.currentUser = currentUser(role)
        const latest = {...CATALOG_ENTRY, id: '22222222-2222-4222-8222-222222222222', version: 2}
        catalogMock.mockResolvedValue([latest])
        vi.mocked(getModelCatalogHistory).mockResolvedValue([latest, CATALOG_ENTRY])
        render(<MemoryRouter initialEntries={['/admin/models?section=diagnostics']}><AdminModelsPage /></MemoryRouter>)
        const characteristics = await screen.findByRole('button', {name: 'Характеристики модели'})
        fireEvent.click(characteristics)
        const decisionInput = screen.getByRole('textbox', {name: 'Идентификатор решения безопасности (UUID)'})
        fireEvent.change(decisionInput, {target: {value: 'local draft'}})
        const historyButton = screen.getByRole('button', {name: 'Открыть историю версий'})
        historyButton.focus()
        fireEvent.click(historyButton)
        const selected = (await screen.findByText('Версия из диагностики')).closest('li')
        expect(selected).toHaveTextContent('Версия 1')
        expect(selected).toHaveAttribute('data-navigation-target', 'true')
        expect(screen.getByText('Версия 2').closest('li')).not.toHaveAttribute('data-navigation-target')
        expect(getModelCatalogHistory).toHaveBeenCalledWith(CATALOG_ENTRY.modelKey, expect.anything())
        expect(screen.getByRole('tab', {name: 'Диагностика', hidden: true})).toHaveAttribute('aria-selected', 'true')
        fireEvent.click(screen.getByRole('button', {name: 'Закрыть окно'}))
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
        expect(screen.getByRole('tab', {name: 'Диагностика'})).toHaveAttribute('aria-selected', 'true')
        expect(characteristics).toHaveAttribute('aria-expanded', 'true')
        expect(decisionInput).toHaveValue('local draft')
        expect(runtimeProbeMock).not.toHaveBeenCalled()
        fireEvent.click(historyButton)
        await screen.findByText('Версия из диагностики')
        fireEvent.keyDown(document, {key: 'Escape'})
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
        expect(screen.getByRole('tab', {name: 'Диагностика'})).toHaveAttribute('aria-selected', 'true')
        fireEvent.click(screen.getByRole('tab', {name: 'Каталог'}))
        fireEvent.click(screen.getByRole('button', {name: /История версий/}))
        await screen.findByRole('dialog')
        fireEvent.click(screen.getByRole('button', {name: 'Закрыть окно'}))
        expect(screen.getByRole('tab', {name: 'Каталог'})).toHaveAttribute('aria-selected', 'true')
    })

    it.each(['catalog', 'diagnostics'])('прямая ссылка из %s на исчезнувшую версию не выдаёт другую за целевую', async (section) => {
        authMock.currentUser = currentUser('ADMIN')
        vi.mocked(getModelCatalogHistory).mockResolvedValue([CATALOG_ENTRY])
        const params = new URLSearchParams({section, modelKey: CATALOG_ENTRY.modelKey,
            versionId: '99999999-9999-4999-8999-999999999999'})
        render(<MemoryRouter initialEntries={[`/admin/models?${params}`]}><AdminModelsPage /></MemoryRouter>)
        expect(await screen.findByText(/Версия из ссылки не найдена в истории модели/)).toBeVisible()
        expect(screen.queryByText('Версия из диагностики')).not.toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', {name: 'Закрыть окно'}))
        expect(screen.getByRole('tab', {name: section === 'catalog' ? 'Каталог' : 'Диагностика'})).toHaveAttribute('aria-selected', 'true')
    })

    it('фильтрует каталог по имени, провайдеру и статусу последней версии без повторных GET', async () => {
        authMock.currentUser = currentUser('ADMIN')
        catalogMock.mockResolvedValueOnce([CATALOG_ENTRY, {
            ...CATALOG_ENTRY, id: '22222222-2222-4222-8222-222222222222',
            modelKey: 'other:other-model', displayName: 'Other Model', provider: 'other', providerModelId: 'other-model', lifecycle: 'DISABLED',
        }])
        render(<MemoryRouter initialEntries={['/admin/models?section=catalog']}><AdminModelsPage /></MemoryRouter>)
        const search = await screen.findByRole('searchbox', { name: 'Поиск модели' })
        fireEvent.change(search, { target: { value: 'OTHER' } })
        expect(screen.getByText('Other Model')).toBeInTheDocument()
        expect(screen.queryByText(CATALOG_ENTRY.displayName, { exact: true })).not.toBeInTheDocument()
        fireEvent.change(screen.getByLabelText('Провайдер'), { target: { value: 'mock' } })
        expect(screen.getByText('Модели не найдены')).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: 'Сбросить' }))
        fireEvent.change(screen.getByLabelText('Статус последней версии'), { target: { value: 'DISABLED' } })
        expect(screen.getByText('Other Model')).toBeInTheDocument()
        expect(screen.getByText(/Показано: 1 из 2/)).toBeInTheDocument()
        expect(catalogMock).toHaveBeenCalledTimes(1)
    })

    it('ADMIN видит подключённую модель и правила, но не может менять глобальный каталог', async () => {
        authMock.currentUser =
            currentUser('ADMIN')

        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)

        expect(
            await screen.findByText(
                'Управление AI-моделями',
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

        expect(screen.getByText('Каталог изменяет суперадминистратор.'))
            .toBeInTheDocument()
        expect(screen.queryByRole('button', {name: 'Создать модель'})).not.toBeInTheDocument()
        expect(screen.queryByRole('dialog', {name: 'Модель каталога'}))
            .not.toBeInTheDocument()
        fireEvent.click(screen.getByRole('tab', { name: 'Обзор' }))
        expect(screen.getByRole('heading', {name: 'Предварительная проверка совместимости'})).toBeInTheDocument()
        const summary = screen.getByText('Показать подробности')
        expect(summary.closest('details')).not.toHaveAttribute('open')
        expect(screen.getByRole('button', {name: 'Открыть проверку правил'})).toBeVisible()
        expect(screen.getByText('Требует внимания')).toBeVisible()
        fireEvent.click(summary.closest('summary')!)
        expect(summary.closest('details')).toHaveAttribute('open')
        fireEvent.click(summary.closest('summary')!)
        expect(summary.closest('details')).not.toHaveAttribute('open')
        expect(screen.getAllByText('Не настроены').length).toBeGreaterThan(0)

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
            'Управление AI-моделями',
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
                'Управление AI-моделями',
            ),
        ).toBeInTheDocument()

        expect(screen.getAllByText(/Правила не настроены|Не настроены/).length).toBeGreaterThan(0)
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

        expect(screen.queryByText('Каталог изменяет суперадминистратор.'))
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

    it('каталог предоставляет собственный viewport с естественной высотой без лишней ручки', async () => {
        authMock.currentUser = currentUser('ADMIN')
        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)
        await screen.findByRole('tab', { name: 'Каталог' })
        fireEvent.click(screen.getByRole('tab', { name: 'Каталог' }))
        const viewport = screen.getByRole('region', { name: 'Таблица каталога моделей' })
        expect(viewport).toBeInTheDocument()
        expect(viewport.style.height).toBe('')
        expect(viewport.style.maxHeight).toMatch(/^\d+px$/)
        expect(screen.queryByRole('separator', { name: 'Изменить высоту таблицы каталога' })).not.toBeInTheDocument()
    })

    it('показывает окно правил по явной кнопке и закрывает его крестиком', async () => {
        authMock.currentUser = currentUser('ADMIN')
        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)
        await screen.findByRole('tab', { name: 'Доступ' })
        fireEvent.click(screen.getByRole('tab', { name: 'Доступ' }))
        fireEvent.click(screen.getByRole('button', {name: 'Настроить правила'}))
        expect(screen.getByRole('dialog', {name: 'Настройка правил использования моделей'}))
            .toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', {name: 'Закрыть окно'}))
        expect(screen.queryByRole('dialog', {name: 'Настройка правил использования моделей'}))
            .not.toBeInTheDocument()
    })

    it.each(['ADMIN', 'SUPER_ADMIN'] as const)('для %s каждая карточка открывает конкретное поле без изменения правил', async (role) => {
        authMock.currentUser = currentUser(role)
        render(<MemoryRouter initialEntries={['/admin/models?section=access']}><AdminModelsPage /></MemoryRouter>)
        const shortcuts = [
            ['Настроить: Модель по умолчанию', 'Доступ', '-default'],
            ['Настроить: Разрешения и запреты', 'Доступ', '-access'],
            ['Настроить: Лимиты токенов', 'Лимиты', '-limits'],
            ['Настроить: Лимит стоимости запроса', 'Бюджет', '-request-cost'],
            ['Настроить: Месячный бюджет', 'Бюджет', '-monthly-budget'],
            ['Настроить: Требования к данным и стоимости', 'Требования', '-data'],
            ['Открыть проверку правил', 'Проверка', '-panel-preview'],
        ]
        for (const [name, tab, suffix] of shortcuts) {
            const button = await screen.findByRole('button', {name})
            button.focus()
            fireEvent.click(button)
            const dialog = screen.getByRole('dialog')
            const selected = dialog.querySelector('[role="tab"][aria-selected="true"]')
            expect(selected).toHaveTextContent(tab)
            const formId = dialog.querySelector('form')?.id
            expect(dialog.querySelector(`[id="${formId}${suffix}"]`)).toHaveFocus()
            expect(dialog.querySelector('form')).toHaveAttribute('data-highlight-target', suffix.slice(1))
            fireEvent.click(selected!)
            expect(dialog.querySelector('form')).not.toHaveAttribute('data-highlight-target')
            expect(screen.getByText('Изменений нет')).toBeInTheDocument()
            fireEvent.click(screen.getByRole('button', {name: 'Закрыть окно'}))
            expect(screen.getByRole('tab', {name: 'Доступ'})).toHaveAttribute('aria-selected', 'true')
        }
        expect(previewOrganizationModelPolicy).not.toHaveBeenCalled()
        expect(createOrganizationModelPolicyVersion).not.toHaveBeenCalled()
        fireEvent.click(screen.getByRole('button', {name: 'Настроить правила'}))
        expect(screen.getByRole('dialog').querySelector('[role="tab"][aria-selected="true"]')).toHaveTextContent('Доступ')
    }, 10000)

    it('открывает проверку из обзора сразу на вкладке проверки без серверного вызова', async () => {
        authMock.currentUser = currentUser('ADMIN')
        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)
        fireEvent.click(await screen.findByRole('button', {name: 'Открыть проверку правил'}))
        expect(screen.getByRole('dialog').querySelector('[role="tab"][aria-selected="true"]')).toHaveTextContent('Проверка')
        expect(previewOrganizationModelPolicy).not.toHaveBeenCalled()
        fireEvent.keyDown(document, {key: 'Escape'})
        expect(screen.getByRole('tab', {name: 'Обзор'})).toHaveAttribute('aria-selected', 'true')
    })

    it('диагностика маршрутизации проверяет UUID до запроса к backend', async () => {
        authMock.currentUser =
            currentUser('ADMIN')

        render(<MemoryRouter initialEntries={['/admin/models']}><AdminModelsPage /></MemoryRouter>)

        await screen.findByRole('tab', { name: 'Диагностика' })
        fireEvent.click(screen.getByRole('tab', { name: 'Диагностика' }))

        fireEvent.change(
            screen.getByLabelText(
                'Идентификатор решения безопасности (UUID)',
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
                {name: 'Найти решение'},
            ),
        )

        expect(
            await screen.findByText(
                'Введите номер решения из журнала событий, а не идентификатор пользователя.',
            ),
        ).toBeInTheDocument()

        expect(
            routeDecisionMock,
        ).not.toHaveBeenCalled()
    })
})




