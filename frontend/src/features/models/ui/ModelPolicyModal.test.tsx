// frontend/src/features/models/ui/ModelPolicyModal.test.tsx
/* ============================================================
   frontend/src/features/models/ui/ModelPolicyModal.test.tsx
   ============================================================ */
import {
    fireEvent,
    render,
    screen,
    waitFor,
} from '@testing-library/react'
import {
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import type {
    CreateOrganizationModelPolicyVersionRequest,
    ModelCatalogEntry,
    ModelPolicyPreview,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../entities/model/api/modelApi'
import { ModelPolicyModal } from './ModelPolicyModal'

const ORGANIZATION_ID =
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

const ORGANIZATION_NAME =
    'Demo Company'

const RUNTIME: RuntimeModelStatus = {
    provider: 'openai',
    model: 'gpt-5',
    enabled: true,
    routingMode: 'SINGLE_PROVIDER_STATIC',
    maxInputTokens: 64_000,
    maxOutputTokens: 8_192,
    toolsSupported: false,
    visionSupported: false,
    structuredOutputSupported: false,
    dataRetentionStatus: 'NOT_DECLARED',
    healthStatus: 'NOT_PROBED',
    pricingStatus: 'UNPRICED',
    inputUsdPer1mTokens: null,
    outputUsdPer1mTokens: null,
    pricingVersion: null,
}

const POLICY: OrganizationModelPolicy = {
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
}

const CATALOG: ModelCatalogEntry[] = [
    {
        id: '11111111-1111-4111-8111-111111111111',
        modelKey: 'openai:gpt-5',
        version: 1,
        provider: 'openai',
        providerModelId: 'gpt-5',
        displayName: 'GPT-5',
        lifecycle: 'ACTIVE',
        maxInputTokens: 64_000,
        maxOutputTokens: 8_192,
        capabilities: [],
        inputModalities: ['TEXT'],
        outputModalities: ['TEXT'],
        retentionStatus: 'NOT_DECLARED',
        retentionDays: null,
        trainingUseStatus: 'NOT_DECLARED',
        pricingStatus: 'UNPRICED',
        pricingComplete: false,
        inputUsdPer1mTokens: null,
        cachedInputUsdPer1mTokens: null,
        cacheWriteInputUsdPer1mTokens: null,
        outputUsdPer1mTokens: null,
        extraPricingJson: '{}',
        pricingVersion: null,
        effectiveFrom: '2026-08-29T10:00:00Z',
        source: 'MANUAL',
        createdByUserId: '22222222-2222-4222-8222-222222222222',
        createdAt: '2026-08-29T09:00:00Z',
    },
]

type PolicySubmit = (
    request: CreateOrganizationModelPolicyVersionRequest,
) => Promise<void>

type PolicyPreviewFn = (
    request: CreateOrganizationModelPolicyVersionRequest,
) => Promise<ModelPolicyPreview>

function renderPolicyModal({
    catalog = [],
    effectiveCatalog = catalog,
    onPreview = vi.fn<PolicyPreviewFn>()
        .mockRejectedValue(new Error('Preview не ожидался в этом тесте')),
    onSubmit = vi.fn<PolicySubmit>()
        .mockResolvedValue(undefined),
}: {
    catalog?: ModelCatalogEntry[]
    effectiveCatalog?: ModelCatalogEntry[]
    onPreview?: PolicyPreviewFn
    onSubmit?: PolicySubmit
} = {}) {
    return render(
        <ModelPolicyModal
            policy={POLICY}
            catalog={catalog}
            effectiveCatalog={effectiveCatalog}
            runtime={RUNTIME}
            organizationId={ORGANIZATION_ID}
            organizationName={ORGANIZATION_NAME}
            pending={false}
            onClose={vi.fn()}
            onPreview={onPreview}
            onSubmit={onSubmit}
        />,
    )
}

describe('ModelPolicyModal', () => {
    it('показывает пользовательские названия без внутренних SOFT/runtime/policy формулировок', () => {
        renderPolicyModal()

        expect(
            screen.getByRole(
                'heading',
                {
                    name: 'Правила использования моделей',
                },
            ),
        ).toBeInTheDocument()

        expect(
            screen.getByText(
                'Это первая настройка правил. После сохранения появится версия 1.',
            ),
        ).toBeInTheDocument()

        expect(
            screen.getByText(
                ORGANIZATION_NAME,
            ),
        ).toBeInTheDocument()

        fireEvent.click(screen.getByRole('tab', {name: /Бюджет/}))
        expect(
            screen.getByRole(
                'option',
                {
                    name: 'Мягкий — предупреждать',
                },
            ),
        ).toBeInTheDocument()

        expect(
            screen.getByRole(
                'option',
                {
                    name: 'Жёсткий — блокировать',
                },
            ),
        ).toBeInTheDocument()

        fireEvent.click(screen.getByRole('tab', {name: /Доступ/}))
        expect(
            screen.getByRole(
                'button',
                {
                    name: /Использовать подключённую модель/,
                },
            ),
        ).toBeInTheDocument()

        expect(
            screen.queryByText(
                /policy v1/i,
            ),
        ).not.toBeInTheDocument()
    })

    it('позволяет менять размер окна с любой грани и угла', () => {
        renderPolicyModal()

        const dialog =
            screen.getByRole('dialog')

        expect(dialog)
            .toHaveClass(
                'modal-card--resizable',
            )

        const handles =
            dialog.querySelectorAll(
                '[data-modal-resize-handle]',
            )

        expect(handles)
            .toHaveLength(8)

        expect(
            Array.from(handles).map(
                (handle) =>
                    handle.getAttribute(
                        'data-modal-resize-handle',
                    ),
            ),
        ).toEqual([
            'n',
            'ne',
            'e',
            'se',
            's',
            'sw',
            'w',
            'nw',
        ])
    })

    it('меняет подпись статуса при включении правил', () => {
        renderPolicyModal()

        expect(
            screen.getByText(
                'Правила выключены',
            ),
        ).toBeInTheDocument()

        expect(
            screen.getByText(
                'Первая настройка не включится, пока администратор не активирует её явно.',
            ),
        ).toBeInTheDocument()

        fireEvent.click(
            screen.getByRole(
                'checkbox',
                {name: /Правила выключены/},
            ),
        )

        expect(
            screen.getByText(
                'Правила включены',
            ),
        ).toBeInTheDocument()

        expect(
            screen.getByText(
                'Ограничения и лимиты применяются к запросам этой организации.',
            ),
        ).toBeInTheDocument()
    })

    it('переключает вкладки семантически, а поля скрытых разделов недоступны', () => {
        renderPolicyModal()
        expect(screen.getByRole('tab', {name: /Доступ/})).toHaveAttribute('aria-selected', 'true')
        expect(screen.queryByPlaceholderText('Например: 32000')).not.toBeVisible()

        fireEvent.click(screen.getByRole('tab', {name: /Лимиты/}))
        expect(screen.getByRole('tab', {name: /Лимиты/})).toHaveAttribute('aria-selected', 'true')
        expect(screen.getByPlaceholderText('Например: 32000')).toBeVisible()
        expect(screen.queryByRole('combobox', {name: 'Разрешённые модели'})).not.toBeInTheDocument()

        fireEvent.click(screen.getByRole('tab', {name: /Бюджет/}))
        expect(screen.getByLabelText(/Контроль бюджета/)).toBeVisible()

        fireEvent.click(screen.getByRole('tab', {name: /Требования к данным/}))
        expect(screen.getByRole('checkbox', {name: 'Полные данные о стоимости'})).toBeVisible()
    })

    it('стрелки, Home и End переключают вкладки без потери фокуса', () => {
        renderPolicyModal()
        const access = screen.getByRole('tab', {name: /Доступ/})
        access.focus()
        fireEvent.keyDown(access, {key: 'ArrowRight'})
        const limits = screen.getByRole('tab', {name: /Лимиты/})
        expect(limits).toHaveFocus()
        expect(limits).toHaveAttribute('aria-selected', 'true')
        fireEvent.keyDown(limits, {key: 'End'})
        const preview = screen.getByRole('tab', {name: /Проверка/})
        expect(preview).toHaveFocus()
        expect(preview).toHaveAttribute('aria-selected', 'true')
        fireEvent.keyDown(preview, {key: 'Home'})
        expect(access).toHaveFocus()
    })

    it('сохраняет первую версию правил с optimistic version 0', async () => {
        const onSubmit = vi.fn<PolicySubmit>()
            .mockResolvedValue(undefined)

        renderPolicyModal({
            onSubmit,
        })

        fireEvent.click(
            screen.getByRole(
                'button',
                {
                    name: 'Сохранить правила',
                },
            ),
        )

        await waitFor(() => {
            expect(onSubmit)
                .toHaveBeenCalledWith(
                    expect.objectContaining({
                        expectedPreviousVersion: 0,
                        enabled: false,
                        budgetEnforcement: 'SOFT',
                    }),
                )
        })
    })

    it('выбирает модель из каталога и отправляет нормализованный allowlist', async () => {
        const onSubmit = vi.fn<PolicySubmit>()
            .mockResolvedValue(undefined)

        renderPolicyModal({
            catalog: CATALOG,
            effectiveCatalog: CATALOG,
            onSubmit,
        })

        const allowedModelsInput =
            screen.getByRole(
                'combobox',
                {name: 'Разрешённые модели'},
            )

        fireEvent.change(
            allowedModelsInput,
            {
                target: {
                    value: 'GPT-5',
                },
            },
        )

        fireEvent.click(
            screen.getByRole(
                'option',
                {name: /GPT-5 openai:gpt-5/},
            ),
        )

        fireEvent.click(
            screen.getByRole(
                'button',
                {name: 'Сохранить правила'},
            ),
        )

        await waitFor(() => {
            expect(onSubmit)
                .toHaveBeenCalledWith(
                    expect.objectContaining({
                        allowModelKeys: [
                            'openai:gpt-5',
                        ],
                    }),
                )
        })
    })

    it('не сохраняет форму, пока в поиске остался незавершённый ввод', async () => {
        const onSubmit = vi.fn<PolicySubmit>()
            .mockResolvedValue(undefined)

        renderPolicyModal({
            catalog: CATALOG,
            effectiveCatalog: CATALOG,
            onSubmit,
        })

        fireEvent.change(
            screen.getByRole(
                'combobox',
                {name: 'Разрешённые модели'},
            ),
            {
                target: {
                    value: 'gpt',
                },
            },
        )

        fireEvent.click(
            screen.getByRole(
                'button',
                {name: 'Сохранить правила'},
            ),
        )

        expect(
            await screen.findByText(
                /Завершите добавление модели/,
            ),
        ).toBeInTheDocument()

        expect(onSubmit)
            .not.toHaveBeenCalled()
    })
})

