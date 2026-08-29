import {
    useState,
} from 'react'
import {
    fireEvent,
    render,
    screen,
} from '@testing-library/react'
import {
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import type {
    ModelCatalogEntry,
} from '../../../api/modelApi'
import {
    DefaultModelSelector,
    ModelKeySelector,
} from './ModelPolicyModelSelectors'

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
    {
        id: '33333333-3333-4333-8333-333333333333',
        modelKey: 'openai:gpt-5-mini',
        version: 1,
        provider: 'openai',
        providerModelId: 'gpt-5-mini',
        displayName: 'GPT-5 mini',
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

type SelectorHarnessProps = {
    initialValue?: string
    conflictingValue?: string
    kind?: 'allow' | 'deny'
}

function SelectorHarness({
    initialValue = '',
    conflictingValue = '',
    kind = 'allow',
}: SelectorHarnessProps) {
    const [value, setValue] =
        useState(initialValue)

    return (
        <ModelKeySelector
            label={
                kind === 'allow'
                    ? 'Разрешённые модели'
                    : 'Запрещённые модели'
            }
            hint="Подсказка"
            kind={kind}
            catalog={CATALOG}
            value={value}
            conflictingValue={conflictingValue}
            onChange={setValue}
        />
    )
}

describe('ModelKeySelector', () => {
    it('нормализует регистр ручного ключа и не создаёт дубликаты', () => {
        const {container} = render(
            <SelectorHarness />,
        )

        const input = screen.getByRole(
            'combobox',
            {name: 'Разрешённые модели'},
        )

        fireEvent.change(input, {
            target: {value: 'OpenAI:GPT-5'},
        })
        fireEvent.keyDown(input, {
            key: 'Enter',
        })

        expect(
            container.querySelectorAll(
                '.models-model-chip',
            ),
        ).toHaveLength(1)
        expect(
            container.querySelector(
                '.models-model-chip code',
            ),
        ).toHaveTextContent('openai:gpt-5')

        fireEvent.change(input, {
            target: {value: 'OPENAI:GPT-5'},
        })
        fireEvent.keyDown(input, {
            key: 'Enter',
        })

        expect(
            container.querySelectorAll(
                '.models-model-chip',
            ),
        ).toHaveLength(1)
    })

    it('не добавляет невалидный ручной ключ', () => {
        const {container} = render(
            <SelectorHarness />,
        )

        const input = screen.getByRole(
            'combobox',
            {name: 'Разрешённые модели'},
        )

        fireEvent.change(input, {
            target: {value: 'openai:gpt 5'},
        })
        fireEvent.keyDown(input, {
            key: 'Enter',
        })

        expect(
            screen.getByRole('alert'),
        ).toHaveTextContent(
            'Ключ модели может содержать только латинские буквы',
        )
        expect(
            container.querySelectorAll(
                '.models-model-chip',
            ),
        ).toHaveLength(0)
    })

    it('разрешает валидный будущий ключ и помечает его как отсутствующий в каталоге', () => {
        render(
            <SelectorHarness />,
        )

        const input = screen.getByRole(
            'combobox',
            {name: 'Разрешённые модели'},
        )

        fireEvent.change(input, {
            target: {value: 'OpenAI:GPT-6'},
        })
        fireEvent.keyDown(input, {
            key: 'Enter',
        })

        expect(
            screen.getByText('openai:gpt-6'),
        ).toBeInTheDocument()
        expect(
            screen.getByText('нет в каталоге'),
        ).toBeInTheDocument()
    })

    it('не позволяет добавить модель из противоположного списка', () => {
        render(
            <SelectorHarness
                kind="deny"
                conflictingValue="openai:gpt-5"
            />,
        )

        const input = screen.getByRole(
            'combobox',
            {name: 'Запрещённые модели'},
        )

        fireEvent.change(input, {
            target: {value: 'OPENAI:GPT-5'},
        })
        fireEvent.keyDown(input, {
            key: 'Enter',
        })

        expect(
            screen.getByRole('alert'),
        ).toHaveTextContent(
            'Эта модель уже находится в списке разрешённых.',
        )
    })

    it('позволяет выбрать модель из каталога по названию', () => {
        const {container} = render(
            <SelectorHarness />,
        )

        const input = screen.getByRole(
            'combobox',
            {name: 'Разрешённые модели'},
        )

        fireEvent.change(input, {
            target: {value: 'mini'},
        })

        fireEvent.click(
            screen.getByRole(
                'option',
                {name: /GPT-5 mini/},
            ),
        )

        expect(
            container.querySelector(
                '.models-model-chip code',
            ),
        ).toHaveTextContent(
            'openai:gpt-5-mini',
        )
    })

    it('при массовой вставке нормализует регистр и удаляет дубликаты', () => {
        const {container} = render(
            <SelectorHarness />,
        )

        const input = screen.getByRole(
            'combobox',
            {name: 'Разрешённые модели'},
        )

        fireEvent.paste(input, {
            clipboardData: {
                getData: () =>
                    'OpenAI:GPT-5\nopenai:gpt-5-mini;OPENAI:GPT-5',
            },
        })

        expect(
            container.querySelectorAll(
                '.models-model-chip',
            ),
        ).toHaveLength(2)
    })
})

describe('DefaultModelSelector', () => {
    it('показывает только разрешённые модели и поддерживает будущий ключ из allowlist', () => {
        const onChange = vi.fn()

        render(
            <DefaultModelSelector
                catalog={CATALOG}
                allowModelKeys={
                    'openai:gpt-5\nopenai:gpt-6'
                }
                denyModelKeys="openai:gpt-5-mini"
                value=""
                onChange={onChange}
            />,
        )

        fireEvent.click(
            screen.getByRole(
                'button',
                {name: /Использовать подключённую модель/},
            ),
        )

        expect(
            screen.getByRole(
                'option',
                {name: /GPT-5 openai:gpt-5/},
            ),
        ).toBeInTheDocument()

        expect(
            screen.queryByRole(
                'option',
                {name: /GPT-5 mini/},
            ),
        ).not.toBeInTheDocument()

        fireEvent.click(
            screen.getByRole(
                'option',
                {name: /openai:gpt-6/},
            ),
        )

        expect(onChange)
            .toHaveBeenCalledWith(
                'openai:gpt-6',
            )
    })
})
