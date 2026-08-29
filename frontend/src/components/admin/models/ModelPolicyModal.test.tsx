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
    ModelCatalogEntry,
    OrganizationModelPolicy,
} from '../../../api/modelApi'
import { ModelPolicyModal } from './ModelPolicyModal'

const ORGANIZATION_ID =
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

const POLICY: OrganizationModelPolicy = {
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

describe('ModelPolicyModal', () => {
    it('показывает пользовательские названия без внутренних SOFT/runtime/policy формулировок', () => {
        render(
            <ModelPolicyModal
                policy={POLICY}
                catalog={[]}
                organizationId={ORGANIZATION_ID}
                pending={false}
                onClose={vi.fn()}
                onSubmit={vi.fn()}
            />,
        )

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
                'Это первая настройка правил для организации. После сохранения появится версия 1.',
            ),
        ).toBeInTheDocument()

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
        render(
            <ModelPolicyModal
                policy={POLICY}
                catalog={[]}
                organizationId={ORGANIZATION_ID}
                pending={false}
                onClose={vi.fn()}
                onSubmit={vi.fn()}
            />,
        )

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

    it('меняет подпись статуса при выключении правил', () => {
        render(
            <ModelPolicyModal
                policy={POLICY}
                catalog={[]}
                organizationId={ORGANIZATION_ID}
                pending={false}
                onClose={vi.fn()}
                onSubmit={vi.fn()}
            />,
        )

        expect(
            screen.getByText(
                'Правила включены',
            ),
        ).toBeInTheDocument()

        fireEvent.click(
            screen.getByRole(
                'checkbox',
                {name: /Правила включены/},
            ),
        )

        expect(
            screen.getByText(
                'Правила выключены',
            ),
        ).toBeInTheDocument()

        expect(
            screen.getByText(
                'Сейчас ограничения этой организации отключены.',
            ),
        ).toBeInTheDocument()
    })

    it('быстрые разделы переводят фокус к соответствующим настройкам', () => {
        render(
            <ModelPolicyModal
                policy={POLICY}
                catalog={[]}
                organizationId={ORGANIZATION_ID}
                pending={false}
                onClose={vi.fn()}
                onSubmit={vi.fn()}
            />,
        )
        const allowedModelsInput =
            screen.getByRole(
                'combobox',
                {name: 'Разрешённые модели'},
            )

        fireEvent.click(
            screen.getByRole(
                'button',
                {name: 'Доступ'},
            ),
        )
        expect(allowedModelsInput)
            .toHaveFocus()

        fireEvent.click(
            screen.getByRole(
                'button',
                {name: 'Лимиты'},
            ),
        )
        expect(
            screen.getByPlaceholderText(
                'Например: 32000',
            ),
        ).toHaveFocus()

        fireEvent.click(
            screen.getByRole(
                'button',
                {name: 'Бюджет'},
            ),
        )
        expect(
            screen.getByLabelText(
                /Контроль бюджета/,
            ),
        ).toHaveFocus()

        fireEvent.click(
            screen.getByRole(
                'button',
                {name: 'Требования к данным'},
            ),
        )
        expect(
            screen.getByRole(
                'checkbox',
                {
                    name: 'Полные данные о стоимости',
                },
            ),
        ).toHaveFocus()
    })

    it('сохраняет первую версию правил с optimistic version 0', async () => {
        const onSubmit = vi.fn()
            .mockResolvedValue(undefined)

        render(
            <ModelPolicyModal
                policy={POLICY}
                catalog={[]}
                organizationId={ORGANIZATION_ID}
                pending={false}
                onClose={vi.fn()}
                onSubmit={onSubmit}
            />,
        )

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
                        enabled: true,
                        budgetEnforcement: 'SOFT',
                    }),
                )
        })
    })

    it('выбирает модель из каталога и отправляет нормализованный allowlist', async () => {
        const onSubmit = vi.fn()
            .mockResolvedValue(undefined)

        render(
            <ModelPolicyModal
                policy={POLICY}
                catalog={CATALOG}
                organizationId={ORGANIZATION_ID}
                pending={false}
                onClose={vi.fn()}
                onSubmit={onSubmit}
            />,
        )

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
        const onSubmit = vi.fn()
            .mockResolvedValue(undefined)

        render(
            <ModelPolicyModal
                policy={POLICY}
                catalog={CATALOG}
                organizationId={ORGANIZATION_ID}
                pending={false}
                onClose={vi.fn()}
                onSubmit={onSubmit}
            />,
        )

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
