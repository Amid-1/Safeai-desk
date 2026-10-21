// frontend/src/features/usage/ui/UsageFilterCombobox.test.tsx
// ============================================================
// frontend/src/features/usage/ui/UsageFilterCombobox.test.tsx
// ============================================================

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
import {
    UsageFilterCombobox,
} from './UsageFilterCombobox'
import type {
    UsageFilterOption,
} from './UsageFilterCombobox'

const OPTIONS: UsageFilterOption[] = [
    {
        value: '',
        label: 'Все модели',
        description: 'Без фильтра по модели',
    },
    {
        value: 'mock-safeai',
        label: 'mock-safeai',
        description:
            'Использовалась в выбранном периоде',
    },
    {
        value: 'gpt-5.6',
        label: 'gpt-5.6',
        description:
            'Использовалась в выбранном периоде',
    },
]

describe('UsageFilterCombobox', () => {
    it('выбирает точное значение из списка и не меняет регистр backend value', () => {
        const onChange = vi.fn()

        render(
            <UsageFilterCombobox
                id="model-filter"
                label="Модель"
                value=""
                options={OPTIONS}
                placeholder="Все модели"
                searchPlaceholder="Найти модель"
                emptyMessage="Модели не найдены"
                onChange={onChange}
            />,
        )

        const input = screen.getByRole(
            'combobox',
            {
                name: 'Модель',
            },
        )

        fireEvent.focus(input)
        fireEvent.change(input, {
            target: {
                value: 'MOCK',
            },
        })
        fireEvent.keyDown(input, {
            key: 'Enter',
        })

        expect(onChange)
            .toHaveBeenCalledWith(
                'mock-safeai',
                expect.objectContaining({
                    value: 'mock-safeai',
                }),
            )
    })

    it('не отправляет произвольный текст, которого нет среди вариантов', () => {
        const onChange = vi.fn()

        render(
            <UsageFilterCombobox
                id="model-filter"
                label="Модель"
                value=""
                options={OPTIONS}
                placeholder="Все модели"
                searchPlaceholder="Найти модель"
                emptyMessage="Модели не найдены"
                onChange={onChange}
            />,
        )

        const input = screen.getByRole(
            'combobox',
            {
                name: 'Модель',
            },
        )

        fireEvent.focus(input)
        fireEvent.change(input, {
            target: {
                value: '"Mock-SafeAI"',
            },
        })
        fireEvent.keyDown(input, {
            key: 'Enter',
        })

        expect(onChange)
            .not
            .toHaveBeenCalled()

        expect(
            screen.getByText(
                'Модели не найдены',
            ),
        ).toBeInTheDocument()
    })

    it('позволяет явно вернуться к варианту «Все модели»', () => {
        const onChange = vi.fn()

        render(
            <UsageFilterCombobox
                id="model-filter"
                label="Модель"
                value="mock-safeai"
                options={OPTIONS}
                placeholder="Все модели"
                searchPlaceholder="Найти модель"
                emptyMessage="Модели не найдены"
                onChange={onChange}
            />,
        )

        const input = screen.getByRole(
            'combobox',
            {
                name: 'Модель',
            },
        )

        fireEvent.focus(input)
        fireEvent.keyDown(input, {
            key: 'Enter',
        })

        expect(onChange)
            .toHaveBeenCalledWith(
                '',
                expect.objectContaining({
                    label: 'Все модели',
                }),
            )
    })

    it('поддерживает серверный поиск без повторной фильтрации результатов', () => {
        const onQueryChange = vi.fn()

        render(
            <UsageFilterCombobox
                id="organization-filter"
                label="Организация"
                value=""
                options={[
                    {
                        value: '',
                        label: 'Все организации',
                    },
                    {
                        value:
                            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
                        label: 'ООО «Космос»',
                        description:
                            'Клиентская · aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
                    },
                ]}
                placeholder="Все организации"
                searchPlaceholder="Найти по названию или UUID"
                emptyMessage="Организации не найдены"
                filterLocally={false}
                onQueryChange={onQueryChange}
                onChange={vi.fn()}
            />,
        )

        const input = screen.getByRole(
            'combobox',
            {
                name: 'Организация',
            },
        )

        fireEvent.focus(input)
        fireEvent.change(input, {
            target: {
                value: 'Космос',
            },
        })

        expect(onQueryChange)
            .toHaveBeenLastCalledWith(
                'Космос',
            )

        expect(
            screen.getByText(
                'ООО «Космос»',
            ),
        ).toBeInTheDocument()
    })

    it('не передаёт серверному поиску запрос длиннее maxQueryLength', () => {
        const onQueryChange = vi.fn()

        render(
            <UsageFilterCombobox
                id="organization-filter"
                label="Организация"
                value=""
                options={[]}
                placeholder="Все организации"
                searchPlaceholder="Найти по названию или UUID"
                emptyMessage="Организации не найдены"
                filterLocally={false}
                maxQueryLength={5}
                onQueryChange={onQueryChange}
                onChange={vi.fn()}
            />,
        )

        const input = screen.getByRole(
            'combobox',
            {
                name: 'Организация',
            },
        )

        fireEvent.focus(input)
        fireEvent.change(input, {
            target: {
                value: '123456789',
            },
        })

        expect(onQueryChange)
            .toHaveBeenLastCalledWith(
                '12345',
            )
    })
})
